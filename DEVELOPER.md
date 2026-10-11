# Retryx — Developer Guide

Everything needed to work on Retryx: how it is put together, why it is put together that way, what
every file does, and what breaks when things go wrong.

For what Retryx *is* and how to use it, see [README.md](README.md).

---

## Contents

1. [Running it](#1-running-it)
2. [Architecture](#2-architecture)
3. [The data model](#3-the-data-model)
4. [The delivery pipeline](#4-the-delivery-pipeline)
5. [Security](#5-security)
6. [Configuration reference](#6-configuration-reference)
7. [File-by-file reference](#7-file-by-file-reference)
8. [Testing](#8-testing)
9. [Failure modes and operations](#9-failure-modes-and-operations)
10. [What is deliberately missing](#10-what-is-deliberately-missing)
11. [Interview questions and answers](#11-interview-questions-and-answers)

---

## 1. Running it

### Everything in Docker

```bash
docker compose up --build
```

Five services: `postgres`, `api` (REST + a worker), `worker` (a second worker, no HTTP traffic),
`receiver`, `dashboard`. The second worker exists so multi-worker claiming is observable rather
than claimed.

### Locally, for development

```bash
make db          # Postgres only
make api         # backend on :8080, with a worker
make worker      # a second worker on :8081, no demo seeding
make receiver    # reference receiver on :9090
make dashboard   # Vite dev server on :5173, proxying /api to :8080
```

`make help` lists every target.

### Tests

```bash
make test-unit   # JUnit + Mockito. No Docker.
make test-it     # Testcontainers. Needs Docker.
make test        # both
make typecheck   # the dashboard
```

### Load test

```bash
make load        # restarts the receiver with 20% failures, resets counters, runs k6
```

### Project layout

```
backend/     Spring Boot API + delivery workers
receiver/    Reference receiver and failure injector
frontend/    React + Vite + Tailwind dashboard
load/        k6 script
```

The backend and receiver are separate Maven projects rather than modules of one parent. They are
deployed separately, have no shared code, and — importantly — the receiver implements signature
verification *independently*. If both sides shared an `HmacSigner`, the signature scheme would be
"whatever that class does" rather than a documented protocol, and an incompatibility would be
undetectable from inside this repository.

---

## 2. Architecture

### One process, two roles

The same jar is the API and the worker. `retryx.worker.enabled` decides whether a given process
claims deliveries. In compose, `api` does both and `worker` only delivers.

That is a deliberate simplification over separate services. The two roles share the data model, the
retry policy, the SSRF guard and the signing code; splitting them into separate deployables would
mean either duplicating all of that or extracting a shared library, in exchange for being able to
scale two things independently — which, at this scale, is not a problem anyone has.

What *is* real is that the two roles have different failure characteristics, and the configuration
reflects it: a worker with a dead receiver holds 16 threads parked on sockets, and running it in the
same process as the API is how a receiver outage becomes an API outage. The defence is the
transaction boundary (§4) and a connection pool sized above worker concurrency, not separate
deployables.

### No broker

`deliveries` is the queue. The alternatives and why they were not chosen are in
[§11](#why-skip-locked-and-not-kafkarabbitmqsqs); the short version is that at this scale the
database is already a durable, transactional, queryable queue, and adding a broker means the
delivery state and the queue state live in two systems that can disagree.

### Packages

| Package              | Role                                                                 |
| -------------------- | -------------------------------------------------------------------- |
| `dev.retryx.domain`      | JPA entities and the two enums. The state machine lives here.    |
| `dev.retryx.repository`  | Spring Data interfaces. Every method is owner-scoped.            |
| `dev.retryx.queue`       | **The claim query.** Raw SQL, on purpose.                        |
| `dev.retryx.delivery`    | The worker loop, backoff, retry classification, HTTP sending.    |
| `dev.retryx.security`    | API keys, HMAC, AES-GCM, SSRF, Spring Security config.           |
| `dev.retryx.service`     | Application services. Transaction boundaries are decided here.   |
| `dev.retryx.web`         | Controllers, DTOs, the exception handler.                        |
| `dev.retryx.config`      | Typed properties, HTTP client, scheduler.                        |
| `dev.retryx.bootstrap`   | Startup safety checks and demo seeding.                          |
| `dev.retryx.error`       | Domain exceptions that map to HTTP statuses.                     |

### JPA for CRUD, SQL for the queue

Spring Data JPA handles entity-per-row CRUD, which is what it is good at. Three things use raw SQL
through `JdbcTemplate` instead:

- **the claim query** — `FOR UPDATE SKIP LOCKED` with `UPDATE … RETURNING` has no sensible JPA
  expression, and this is the most important query in the system; hiding it behind a derived method
  name would hide the thing most worth reading
- **stale-claim recovery** — same reason
- **dashboard aggregates** — `count(*) FILTER (WHERE …)` and `percentile_cont` have no JPQL
  equivalent at all, so the alternatives are one query per figure or counting rows in the JVM

---

## 3. The data model

Five tables. The annotated DDL is in
[`V1__initial_schema.sql`](backend/src/main/resources/db/migration/V1__initial_schema.sql).

```
api_keys ──owner_id──┐
                     │
webhook_endpoints ───┼──► deliveries ◄── events
                     │         │
                     │         ▼
                     │    delivery_attempts
```

### `api_keys`

Only a SHA-256 hash of the key is stored, plus a 12-character prefix for display. Authentication is
one lookup on a unique index.

Plain SHA-256 rather than bcrypt, deliberately. A slow KDF buys time against someone brute-forcing
a *guessable* secret; these keys are 24 bytes of `SecureRandom`, so there is nothing to guess at any
cost per guess. What a slow hash would actually buy is 100ms of CPU on the authentication path of
every request — a self-inflicted denial of service.

### `webhook_endpoints`

`encrypted_secret` is an AES-256-GCM envelope: `v1.<nonce>.<ciphertext+tag>`. `secret_preview` is a
non-reversible display hint.

Deletion is soft. Deliveries reference this row forever, and a hard delete would turn the
delivery-detail page — the page someone opens *because* something went wrong — into a dangling
pointer.

### `events`

Immutable. `payload` is `jsonb`.

The idempotency constraint is the important part:

```sql
CREATE UNIQUE INDEX uq_events_owner_idempotency_key
    ON events (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

Scoped to the owner, because two tenants may legitimately both use `"order-1"`. Partial, so rows
without a key are not forced to be unique. And it is the *database* that enforces idempotency, not
application logic — see [§11](#how-is-idempotency-actually-enforced).

### `deliveries` — the queue

One row per `(event, endpoint)`, with a unique index on that pair so fan-out is idempotent.

```
PENDING ──claim──► IN_PROGRESS ──2xx─────────────────► DELIVERED
   ▲                    │
   │                    ├── retryable, budget left ──► PENDING
   │                    ├── permanent | budget gone ─► FAILED
   │                    │
   └── lease expired ───┘   (recovered by StaleClaimReaper)

DELIVERED | FAILED ──replay──► PENDING
```

Three columns carry the concurrency control:

| Column            | Role                                                                           |
| ----------------- | ------------------------------------------------------------------------------ |
| `status`          | Only `PENDING` is claimable                                                    |
| `next_attempt_at` | Backoff. A row is claimable only once this has passed                          |
| `locked_until`    | Lease expiry, written from the **database** clock                              |
| `locked_by`       | Which worker holds it                                                          |

The claim index is partial:

```sql
CREATE INDEX idx_deliveries_claim
    ON deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS');
```

Terminal rows become the overwhelming majority of the table, and keeping them out of the queue's hot
index means index size tracks **backlog depth** rather than total history. Without the predicate, the
claim query's index would grow forever even though the set of claimable rows stays small.

`owner_id` is denormalised from `events`, so every owner-scoped list query and the authorisation
check on `GET /api/deliveries/{id}` is a single-table index scan with no join on the hottest read
path in the dashboard.

There is deliberately **no `@Version`** on this entity. Optimistic locking would fight the queue: the
claim query and the reaper both update rows through raw JDBC and would leave the version column
stale. Mutual exclusion comes from the lease, which is also what survives a worker dying mid-send.
Two mechanisms guarding the same rows would only create spurious failures.

### `delivery_attempts`

Append-only. Never updated, never deleted.

This is why it is a separate table from `deliveries`. A delivery row is current state and is
overwritten on every retry; if the two were merged, the fifth attempt would erase the evidence from
the first four, and "it failed with a 502 twice then timed out" would be unanswerable. Splitting
them gives a mutable row the queue can update cheaply and an immutable log the dashboard renders as
a timeline.

`UNIQUE (delivery_id, attempt_number)` is also a safety net: if two workers ever did send the same
attempt, the second insert would fail loudly rather than silently duplicating a row.

---

## 4. The delivery pipeline

### The transaction boundaries

This is the single most important thing to understand about the worker.

```
 ┌─ transaction 1 ──────────────────────────┐
 │ DeliveryQueueDao.claimDue()              │   ~1ms
 │   WITH due AS (SELECT … FOR UPDATE        │
 │                SKIP LOCKED LIMIT n)       │
 │   UPDATE deliveries SET status='IN_PROGRESS'
 │   RETURNING id                            │
 └─────────────── COMMIT ───────────────────┘

 ┌─ read-only ──────────────────────────────┐
 │ DeliveryQueueDao.loadJobs(ids)           │   one join
 └─────────────── COMMIT ───────────────────┘

   ░░░ NO TRANSACTION ░░░░░░░░░░░░░░░░░░░░░░░   up to 13s
   WebhookSender.send(job)
     • SSRF re-validate
     • build body bytes
     • decrypt secret, sign THOSE bytes
     • POST, bounded response read

 ┌─ transaction 2 ──────────────────────────┐
 │ DeliveryOutcomeRecorder.record()         │   ~1ms
 │   • verify we still hold the lease        │
 │   • INSERT delivery_attempts              │
 │   • UPDATE deliveries (state machine)     │
 └─────────────── COMMIT ───────────────────┘
```

Wrapping all of that in one transaction is the obvious-looking implementation and it fails four ways
at once:

1. a pool connection is held for the duration of someone else's slow server
2. the claimed rows stay locked, so `SKIP LOCKED` can no longer route around them
3. Postgres cannot vacuum anything newer than the oldest open transaction
4. 20 concurrent sends to a dead receiver exhaust a 20-connection pool and take the API down

The fix is not a bigger pool. It is not holding the transaction.

### Why `DeliveryProcessor` and `DeliveryOutcomeRecorder` are separate beans

Not style — correctness. `@Transactional` is implemented with a proxy, and a proxy only intercepts
calls arriving from outside the object. Had `process()` and `record()` been two methods on one bean,
`this.record(...)` would bypass the proxy entirely and run with **no transaction at all**: the
attempt insert and the delivery update would be two separate autocommits, and a crash between them
would leave an attempt logged against a delivery still marked `IN_PROGRESS`. The bug is silent and
the code looks right. Separating the beans makes the call cross a proxy boundary.

The same pattern appears in `EventIngestionService` → `EventFanoutWriter`, for a related reason: the
service needs to *catch* the unique-constraint violation its writer throws and recover from it, which
requires the failing transaction to have already rolled back.

### Clocks

Two clocks, used deliberately differently:

| Thing             | Clock    | Why                                                                                                             |
| ----------------- | -------- | --------------------------------------------------------------------------------------------------------------- |
| `locked_until`    | Database | A lease is distributed mutual exclusion. The only way several machines agree on when it expires is one shared clock. If worker A's clock ran two minutes fast it would consider its own lease expired early and re-send another worker's in-flight delivery. |
| `next_attempt_at` | Java     | A few milliseconds of skew on "retry in eight seconds" changes nothing.                                         |

### Backoff

```
exp(n)   = min(base × multiplier^(n-1), max)
delay(n) = jitter(exp(n))
```

Defaults: base 2s, ×2, cap 1h, 6 attempts → 2s, 4s, 8s, 16s, 32s. A delivery that is going to fail
has done so within about a minute; a receiver down for a few minutes is still caught.

Jitter strategies, in `JitterStrategy`:

- `NONE` — `exp`. Deterministic, for tests and demos.
- `FULL` — `random(0, exp)`. Maximum spread.
- `EQUAL` — `exp/2 + random(0, exp/2)`. **Default.** Guarantees a minimum wait, so a struggling
  receiver always gets at least half the intended breathing room, while still spreading the herd.

`BackoffPolicy` takes its randomness through an injected `DoubleSupplier`, so tests pin it to 0.0
and 1.0 and assert exact boundaries rather than "the number is in a range".

### Retry classification

`RetryClassifier` — the full table is in its Javadoc. The judgement calls:

- **3xx is permanent.** Retryx never follows redirects (that would walk straight through the SSRF
  guard), so a redirect is a configuration error: register the final URL.
- **`UnknownHostException` is retryable.** It looks permanent, and a deleted DNS record is — but
  resolver blips are common, and the budget bounds the cost of being wrong at six attempts, where
  being wrong the other way drops an event.
- **A failed TLS handshake is permanent.** An expired certificate needs an operator, not another
  attempt in 30 seconds. The row lands in `FAILED` where it is visible, and replay sends it once
  fixed.
- **An unexpected exception is retryable.** If Retryx itself throws something unforeseen, the
  conservative reading is "our bug, not their rejection".
- **501 is retryable**, with the rest of 5xx. Arguably a permanent "I don't implement this", but in
  practice far more often a misconfigured gateway in front of a healthy app.

### Signing

```
signed string = "<unix seconds>" + "." + <exact request body bytes>
signature     = hex(HMAC-SHA256(endpoint secret, signed string))
header        = X-Retryx-Signature: t=<unix seconds>,v1=<signature>
```

Four properties, each deliberate:

- **The exact bytes.** `HmacSigner.sign` takes a `byte[]` precisely so the caller is forced to hold
  the bytes it signed and send *those*. A signature over a body regenerated between signing and
  sending is a signature over something else.
- **The timestamp, inside the MAC.** Without it, anyone who captured one valid request can replay it
  forever. A timestamp sent *alongside* an unrelated signature is just a number an attacker edits.
- **`v1=`.** Room to add an algorithm later and let receivers accept both during the migration.
- **The body is byte-identical across retries.** The attempt number lives in a header, not the body,
  so a receiver can deduplicate on a body hash and a replay six months later reproduces exactly what
  was sent the first time.

`EventEnvelopeWriter` never parses the payload. It comes out of `jsonb` as text and is spliced in as
raw JSON. Round-tripping through a parser is where byte-stability dies: key order, number formatting
(`1.0` vs `1`), unicode escaping and whitespace are all at the serialiser's mercy. Postgres
normalises `jsonb` on write, so the text coming back is already canonical — that normalisation
happening *once, at ingest* is what makes splicing safe.

---

## 5. Security

### Authentication

`X-Api-Key: rtx_sk_…` or `Authorization: Bearer rtx_sk_…`.

`ApiKeyAuthenticationFilter` populates the security context and **rejects nothing**. Authorisation
is `SecurityConfig`'s job. Keeping them separate means a new public endpoint is a one-line change to
the rules rather than a condition added to the filter — and the filter cannot accidentally become
the thing that decides what is public.

The last authorisation rule is `anyRequest().denyAll()`, not `authenticated()`. A controller added
tomorrow under an unlisted path is unreachable until someone deliberately opens it. The rules fail
closed.

CSRF is disabled, and that is correct here: CSRF attacks work because browsers attach *ambient*
credentials (cookies) to cross-origin requests automatically. Retryx holds no session and issues no
cookie, so a forged form post from `evil.com` arrives with no `X-Api-Key` and is a 401. Disabling
CSRF on a cookie-authenticated API is a serious hole; on a stateless header-authenticated one,
leaving it on would break every non-browser client for no benefit.

### Owner scoping

Every repository method that reads user data takes `ownerId`. There is deliberately no
`findById(UUID)` in use in the service layer — a lookup by primary key alone is exactly how one
tenant ends up reading another's data: the caller forgets the ownership check, the code still
compiles, and the bug is invisible until someone else's webhook URL shows up in a support ticket.

Controllers take the owner from `@AuthenticationPrincipal`. There is no `ownerId` parameter anywhere,
so the parameter a caller would need to tamper with does not exist.

"Not found" and "exists but is not yours" both answer **404**. Answering 403 for the second turns the
API into an oracle for enumerating other tenants' ids.

### SSRF

Retryx takes a URL from a user and makes an HTTP request to it from inside the deployment's network.
That is the whole product, and it is also a textbook SSRF primitive. Register
`http://169.254.169.254/latest/meta-data/iam/security-credentials/`, send yourself an event, and read
the cloud instance's IAM credentials out of the response snippet on the delivery-detail page.

`SsrfGuard`, in layers:

1. **Scheme allowlist** — http/https. Blocks `file://`, `gopher://` (which can write arbitrary bytes
   to a TCP port, turning SSRF into "run commands on your Redis"), `redis://`, `jar://`.
2. **No credentials in the URL.** `http://expected-host@actual-host/` reads as two different hosts
   depending on which parser you ask. Reject the ambiguity rather than pick a reading.
3. **Port allowlist.** Removes the port scanner.
4. **Hostname denylist** — `localhost`, `*.internal`, `*.local`, the cloud metadata names.
5. **Resolve the name and check *every* address.** A hostile host can publish one public A record and
   one at `127.0.0.1`; checking only the first answer means losing a coin flip. Loopback, link-local
   (covering `169.254.169.254`), private, CGNAT, multicast and reserved ranges are rejected —
   including when smuggled inside an IPv4-mapped, 6to4, NAT64 or Teredo IPv6 address. An IPv4-only
   check accepts `::ffff:127.0.0.1` and then connects to loopback.
6. **No redirect following**, set on the client in `HttpClientConfig`. Without it, every check above
   is bypassed by a public URL answering `302 Location: http://169.254.169.254/`.
7. **Validate twice** — at registration for fast feedback, and again immediately before *every* send.
   The second is the one that counts: DNS changes between registration and delivery, and between
   attempt 1 and attempt 6.

**Known gap: DNS rebinding.** There is still a window between "resolve and validate" and "connect",
and nothing stops a resolver returning a different answer for the connect. Closing it properly means
pinning the validated `InetAddress` and connecting to that IP with the original `Host` header (and
SNI). `SsrfGuard.validate` returns the resolved addresses precisely so a future pinning connection
factory can use them; the current client does not pin. Documented rather than quietly ignored.

**`retryx.ssrf.allow-hosts` is a hole in all of this.** It exempts hosts from the address checks so
the bundled receiver on `localhost` works. The `prod` profile forces it empty, and
`StartupSafetyValidator` logs an error if it is populated in production.

### Secrets at rest

AES-256-GCM, key from `RETRYX_ENCRYPTION_KEY`.

GCM because it is authenticated encryption: the 128-bit tag means a tampered ciphertext fails to
decrypt rather than yielding attacker-influenced plaintext. With unauthenticated CBC, someone with
write access to the database could flip bits in a stored secret and watch what happens.

A fresh 12-byte `SecureRandom` nonce per encryption, stored next to the ciphertext. **Reusing a
nonce under the same key breaks GCM catastrophically** — not "weakens", breaks: two messages under
one nonce leak their XOR and allow recovery of the authentication subkey, after which an attacker
forges tags at will. So the nonce is never derived from the record, never a counter in application
code, never zero.

What this protects against: a stolen dump, a leaked backup, a read-only SQL injection — because the
key lives outside the database. What it does not protect against: someone who already has the
running process's environment. That needs a KMS, which is listed as future work.

### Startup safety

`StartupSafetyValidator` **refuses to start** with the `prod` profile if the bundled development
encryption key is in use, or if the SSRF guard is disabled.

Documentation is not enough for this. The failure mode for "the README said to change it" is a
deployment that works perfectly, passes every test, serves traffic for a year, and is signing
webhooks with a key committed to a public repository. Nothing about it looks wrong at any point. A
deployment that fails to start is a ten-minute problem; one that silently uses a public key is an
incident.

---

## 6. Configuration reference

Every value has a working default. Names are the environment variables; the YAML keys are in
[`application.yml`](backend/src/main/resources/application.yml).

### Worker

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_WORKER_ENABLED` | `true` | `false` makes this an API-only node |
| `RETRYX_WORKER_ID` | hostname + random | **Must be unique per process.** Two workers sharing an id can each pass the other's lease check |
| `RETRYX_WORKER_INITIAL_DELAY_MS` | `2000` | Let the process finish starting before it claims work |
| `RETRYX_WORKER_POLL_MS` | `500` | The latency floor for an already-due row |
| `RETRYX_WORKER_BATCH_SIZE` | `25` | Fewer round trips, but a crash costs a lease window for this many rows |
| `RETRYX_WORKER_CONCURRENCY` | `16` | In-flight sends. **Keep the DB pool above this** |
| `RETRYX_WORKER_LEASE_SECONDS` | `60` | **Must exceed connect + read timeout**, or a slow receiver outlives its own claim and the delivery is sent twice |

### Reaper

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_REAPER_ENABLED` | `true` | |
| `RETRYX_REAPER_INTERVAL_MS` | `5000` | Crash recovery time is bounded by `lease-seconds + this` |

### Retry

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_MAX_ATTEMPTS` | `6` | Per-endpoint override available |
| `RETRYX_BASE_DELAY_SECONDS` | `2` | Delay before attempt 2 |
| `RETRYX_BACKOFF_MULTIPLIER` | `2.0` | |
| `RETRYX_MAX_DELAY_SECONDS` | `3600` | Without a cap, attempt 20 is eleven days out |
| `RETRYX_JITTER` | `EQUAL` | `FULL` \| `EQUAL` \| `NONE`. `NONE` is for tests only |

### HTTP

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_HTTP_CONNECT_TIMEOUT_MS` | `3000` | |
| `RETRYX_HTTP_READ_TIMEOUT_MS` | `10000` | Without a request timeout, a receiver that accepts and never answers parks a worker thread forever |

### Security

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_ENCRYPTION_KEY` | **public dev key** | `openssl rand -base64 32`. The prod profile refuses to start with the default |
| `RETRYX_SSRF_ENABLED` | `true` | Only ever `false` in a test asserting its absence |
| `RETRYX_REQUIRE_HTTPS` | `false` | `true` in prod |
| `RETRYX_ALLOWED_PORTS` | `80,443,8080,9090,8443` | |
| `RETRYX_SSRF_ALLOW_HOSTS` | `localhost,127.0.0.1,receiver,…` | **Development only.** Forced empty in prod |

### Database and misc

| Variable | Default | Notes |
| --- | --- | --- |
| `RETRYX_DB_URL` / `_USER` / `_PASSWORD` | local Postgres | |
| `RETRYX_DB_POOL_SIZE` | `20` | Must exceed worker concurrency with headroom for the web tier |
| `RETRYX_PORT` | `8080` | |
| `RETRYX_CORS_ORIGINS` | `localhost:5173,…` | Never `*` |
| `RETRYX_DEMO_ENABLED` | `true` | Forced `false` in prod |

### Receiver

| Variable | Default | Notes |
| --- | --- | --- |
| `RECEIVER_PORT` | `9090` | |
| `RECEIVER_SIGNING_SECRET` | demo secret | Blank accepts everything unverified |
| `RECEIVER_REJECT_INVALID_SIGNATURE` | `true` | Returns 400, so Retryx stops rather than looping |
| `RECEIVER_FAILURE_RATE` | `0.0` | `0.2` for the load test |
| `RECEIVER_LATENCY_MS` | `0` | |

---

## 7. File-by-file reference

Every source file, with what each public method does.

### `backend` — `dev.retryx`

#### `RetryxApplication.java`
Entry point. `@SpringBootApplication`, `@ConfigurationPropertiesScan` (so `RetryxProperties` binds
without an explicit `@EnableConfigurationProperties`), `@EnableScheduling` (for the worker and
reaper).

- `main(String[])` — boots Spring.

### `dev.retryx.config`

#### `RetryxProperties.java`
A record tree holding every tunable, bound from `retryx.*`. Typed rather than scattered `@Value`
annotations, so a typo in a property name is a startup failure instead of a silent default.

Nested records: `Worker`, `Reaper`, `Retry`, `Http`, `Signing`, `Crypto`, `Ssrf`, `Cors`, `Demo`.
Each component is documented in the record's Javadoc.

#### `HttpClientConfig.java`
- `webhookHttpClient(RetryxProperties)` → `HttpClient` — the shared outbound client.
  `Redirect.NEVER` is a load-bearing security control, not tuning (see §5). HTTP/1.1 is pinned
  because negotiating h2 against thousands of unknown receivers buys nothing for small POSTs and
  occasionally finds a broken proxy.

#### `SchedulingConfig.java`
- `taskScheduler()` → `ThreadPoolTaskScheduler` — pool size **2**. Spring's default `@Scheduled`
  executor is a single thread, and the worker's poll blocks until its whole batch of HTTP sends
  completes — up to thirteen seconds against a dead receiver. On one thread the reaper could only
  run between batches, precisely when the system is least healthy. Also waits for in-flight tasks on
  shutdown, pairing with `server.shutdown: graceful`.

### `dev.retryx.domain`

#### `DeliveryStatus.java`
`PENDING`, `IN_PROGRESS`, `DELIVERED`, `FAILED`. Stored as text, not an ordinal — an ordinal would
silently remap every historical row the first time someone reorders the enum.
- `isTerminal()` — true for `DELIVERED` and `FAILED`.

#### `AttemptOutcome.java`
`SUCCESS`, `RETRYABLE_ERROR`, `PERMANENT_ERROR`, `BLOCKED`. Separate from `DeliveryStatus` because an
attempt can be a retryable error while the delivery ends up delivered, and the last attempt of an
exhausted budget is still only a retryable error even though the delivery is now failed.
- `isRetryable()` — true for `RETRYABLE_ERROR`.

#### `ApiKey.java`
- `isActive()` — not revoked.
- `touch()` — records use. Best-effort, throttled by the caller.
- `revoke()` — idempotent.
- getters.

#### `WebhookEndpoint.java`
- `isLive()` — active and not deleted; the fan-out predicate.
- `setActive(boolean)` — enable/disable without losing history.
- `softDelete()` — idempotent; also deactivates.
- `rename(String)`, `setDescription(String)`, getters.

#### `Event.java`
Immutable. `payload` is a `String` with `@JdbcTypeCode(SqlTypes.JSON)` — held as text so the
delivery path does no deserialise/reserialise round trip and the signed bytes stay stable.
Getters only.

#### `Delivery.java`
The state machine.
- `nextAttemptNumber()` — `attemptCount + 1`.
- `hasBudgetAfter(int)` — whether another attempt is permitted after the given one fails.
- `onSuccess(int, int)` — terminal; releases the lease.
- `onRetryScheduled(int, OffsetDateTime, Integer, String)` — back to `PENDING` with a future
  `nextAttemptAt`.
- `onFailed(int, Integer, String)` — terminal.
- `replay(int extraBudget)` — terminal → `PENDING`, **extending** `maxAttempts` to
  `attemptCount + extraBudget` rather than resetting the count, so attempt numbers stay unique and
  monotonic across replays. Throws if the delivery is still active.
- getters. No setters for queue columns; the only way to move state is through the methods above.

#### `DeliveryAttempt.java`
Append-only. Truncates the error message and response snippet on construction so oversized input
cannot reach the column. Getters only.

### `dev.retryx.repository`

Spring Data interfaces. Every user-data method takes `ownerId`.

- **`ApiKeyRepository`** — `findByKeyHash(String)` (the authentication hot path, served by a unique
  index), `existsByOwnerId(String)`.
- **`WebhookEndpointRepository`** — `findByIdAndOwnerIdAndDeletedAtIsNull` (for writes),
  `findByIdAndOwnerId` (for history views, which must still render a deleted endpoint),
  `findAllByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc`,
  `findAllByOwnerIdAndActiveTrueAndDeletedAtIsNull` (the fan-out target list),
  `countByOwnerIdAndDeletedAtIsNull`.
- **`EventRepository`** — `findByIdAndOwnerId`, `findByOwnerIdAndIdempotencyKey` (the *optimistic*
  half of idempotency), `findAllByOwnerId`, `findAllByOwnerIdAndEventType`, `countByOwnerId`.
- **`DeliveryRepository`** — `findByIdAndOwnerId`,
  `findAllByEventIdAndOwnerIdOrderByCreatedAtAsc`, `findAllByOwnerId`,
  `findAllByOwnerIdAndStatus`, `countByOwnerIdAndStatus`, `countByEventId`. Claiming is **not**
  here.
- **`DeliveryAttemptRepository`** — `findAllByDeliveryIdOrderByAttemptNumberAsc`,
  `countByDeliveryId`. No update or delete path exists anywhere in the application.

### `dev.retryx.queue`

#### `DeliveryJob.java`
An immutable value with everything one send needs, gathered in one join. A plain record rather than a
JPA entity, so no persistence context is open while the HTTP call is in flight.
- `attemptNumber()`, `hasBudgetAfterThisAttempt()`.

#### `DeliveryQueueDao.java`
**The queue.** Raw SQL, on purpose.
- `claimDue(String workerId, int batchSize, int leaseSeconds)` → `List<UUID>` — the
  `FOR UPDATE SKIP LOCKED` CTE plus `UPDATE … RETURNING`. `REQUIRES_NEW` and deliberately tiny:
  it must commit before any HTTP call begins.
- `loadJobs(Collection<UUID>)` → `List<DeliveryJob>` — joins `deliveries × events ×
  webhook_endpoints`. Two queries per batch rather than one per delivery.
- `requeueExpiredLeases(int)` → `List<UUID>` — stale-claim recovery, itself a `SKIP LOCKED` claim so
  several workers can reap concurrently.
- `countDueNow()`, `oldestDueAgeSeconds()`, `countInFlight()` — metrics. Oldest-age is the honest
  "are we keeping up" signal: depth can be high and healthy, but a rising oldest-age means falling
  behind.

### `dev.retryx.delivery`

#### `JitterStrategy.java`
`NONE`, `FULL`, `EQUAL`. The Javadoc explains the thundering herd this exists to prevent.

#### `BackoffPolicy.java`
- `delayAfter(int failedAttemptNumber)` → `Duration` — the capped exponential, jittered, floored at
  250ms (full jitter can draw a near-zero delay, and an immediate retry is a tight loop against a
  failing receiver).
- `nextAttemptAt(int)` → `OffsetDateTime` — now plus the delay.
- `exponentialDelay(int)` → `Duration` — the unjittered series, for display and for testing the
  series separately from the randomisation.
- `defaultMaxAttempts()`, `jitter()`.
- Two constructors: production (properties) and test (config + `DoubleSupplier`).

#### `RetryClassifier.java`
- `classifyStatus(int)` → `AttemptOutcome`.
- `classifyException(Throwable)` → `AttemptOutcome`.
- `describe(Throwable)` → `String` — compact message for the attempt log.
- `rootCause(Throwable)` — private, bounded at depth 10 so a self-referential cause chain cannot
  spin. Client libraries wrap transport failures two or three layers deep, and classifying the
  wrapper is how "every failure is a `ResourceAccessException`" bugs happen.

#### `WebhookHeaders.java`
Header name constants. Collected in one place because they are a public contract — renaming one is a
breaking change for every integration.

#### `EventEnvelopeWriter.java`
- `write(DeliveryJob)` → `byte[]` — builds the request body. Guarantees byte-stability across
  retries and never parses the payload. Uses Jackson's `JsonStringEncoder` for the one field that
  needs escaping, because the set of characters JSON requires escaping is larger and stranger than
  it looks.

#### `SendResult.java`
The outcome of one HTTP attempt, as a value. Factories: `success`, `httpFailure`,
`transportFailure`, `blocked`. `isSuccess()`.

#### `WebhookSender.java`
- `send(DeliveryJob)` → `SendResult` — one HTTP attempt. **Never touches the database** and **never
  throws for a delivery failure.** Re-validates SSRF, builds the body once, signs *those* bytes,
  sends *those same* bytes.
- `BoundedBodyCollector` — private. Collects at most `max-response-snippet-bytes` and discards the
  rest. `ofString()` would be unbounded against a receiver whose response size the user chose.

#### `DeliveryOutcomeRecorder.java`
- `record(DeliveryJob, SendResult, String workerId)` — `REQUIRES_NEW`. Verifies the lease is still
  held (a lost lease is counted as `retryx.lease.lost`, the one condition under which Retryx
  knowingly double-sends), appends the attempt, moves the state machine, increments metrics.

#### `DeliveryProcessor.java`
- `process(DeliveryJob, String workerId)` — send, then record. Deliberately **not** transactional:
  this is the method that spans the network call.

#### `WorkerIdentity.java`
- `id()` — this process's stable identity. Default appends a random suffix to the hostname, because
  two containers on one host share a hostname and two workers sharing an id can each pass the
  other's lease check.

#### `DeliveryWorker.java`
- `poll()` — `@Scheduled(initialDelayString, fixedDelayString)`. Swallows and logs exceptions; a
  scheduled method that throws can be silently never rescheduled, and the worker would appear to
  "just stop".
- `runOnce()` → `int` — claims and processes one batch synchronously. Returns the number attempted.
  Integration tests call this directly instead of waiting on a real scheduler.
- `shutdown()` — `@PreDestroy`. Drains in-flight sends rather than abandoning them.
- `fixedDelay`, not `fixedRate`: `fixedRate` would start a new poll regardless of whether the last
  finished, piling up unbounded concurrent work. `fixedDelay` makes in-flight work bounded by
  `batch-size` and turns the loop into natural backpressure.

#### `StaleClaimReaper.java`
- `sweep()` — `@Scheduled`.
- `recoverOnce()` → `List<UUID>` — requeues one batch of expired leases. Exposed so tests can
  simulate a crash with a zero-second lease instead of waiting out a real one.
- Deliberately does **not** increment `attempt_count`: the worker may have died before sending,
  after sending, or mid-response, and Retryx does not pretend to know which.

### `dev.retryx.security`

#### `ApiKeyHasher.java`
- `generateRawKey()` → `String` — `rtx_sk_` + 24 bytes of `SecureRandom`, base64url.
- `hash(String)` → `String` — SHA-256 hex. The Javadoc explains why not bcrypt.
- `displayPrefix(String)` → `String` — the 12 characters safe to store in clear.
- `looksLikeKey(String)` → `boolean` — cheap shape check, so a junk header costs no hash and no
  query.

#### `HmacSigner.java`
- `sign(String secret, long timestamp, byte[] body)` → hex HMAC.
- `signatureHeader(...)` → `t=…,v1=…`.
- `verify(String secret, String headerValue, byte[] body, long now, long tolerance)` → `boolean` —
  reference verification. Uses `MessageDigest.isEqual`, not `String.equals`, which short-circuits at
  the first differing byte and leaks through timing how much of a forged signature was right.

#### `AesGcmSecretCipher.java`
- `encrypt(String)` → `v1.<nonce>.<ciphertext+tag>`.
- `decrypt(String)` → plaintext. Deliberately vague error: never reports whether the padding, the
  tag or the key was at fault.
- `generateSigningSecret()` → `whsec_…`, 32 random bytes.
- `preview(String)` → display hint.
- Constructor validates the key length at startup.
- `INSECURE_DEV_KEY` — the published default, referenced by `StartupSafetyValidator`.

#### `SsrfGuard.java`
- `validate(String rawUrl)` → `Destination(URI, List<InetAddress>)`; throws
  `BlockedDestinationException`. The returned addresses exist so a future pinning connection factory
  can close the DNS-rebinding gap.
- `isAllowed(String)` → `boolean` — never throws.
- `rejectionReason(InetAddress)`, `rejectionReasonIpv4(byte[])`, `rejectionReasonIpv6(byte[])`,
  `embeddedIpv4Reason(...)` — private. The IPv6 variants unwrap IPv4-mapped, NAT64, 6to4 and Teredo
  addresses and re-check the embedded IPv4, because each is a working bypass of a naive guard.

#### `BlockedDestinationException.java`
Carries a short, user-facing `reason`, used both in the 400 response and in the attempt log — so a
blocked delivery appears in the timeline with an explanation rather than as a silent gap.

#### `ApiKeyPrincipal.java`
`(ownerId, apiKeyId, keyPrefix)`. The tenant boundary for the whole application.

#### `ApiKeyAuthenticator.java`
- `authenticate(String rawKey)` → `Optional<ApiKeyPrincipal>`. One index probe. Does not
  distinguish unknown / malformed / revoked.
- `touchThrottled(ApiKey)` — private. Updates `last_used_at` at most once a minute per key. Writing
  it on every request turns a `GET` into a lookup plus an `UPDATE` plus a WAL record plus a dead
  tuple, and serialises concurrent requests using the same key behind one row lock.

#### `ApiKeyAuthenticationFilter.java`
- `doFilterInternal(...)` — reads `X-Api-Key` or `Authorization: Bearer`, populates the security
  context, rejects nothing. Extends `OncePerRequestFilter` so internal dispatches don't
  re-authenticate and double-count usage.

#### `SecurityConfig.java`
- `filterChain(HttpSecurity, ApiKeyAuthenticationFilter)` → `SecurityFilterChain` — stateless, CSRF
  off (with reasoning), `denyAll()` last, JSON 401/403 bodies in the API's error shape.
- `corsConfigurationSource()` → explicit origin list, never `*`.

### `dev.retryx.service`

#### `WebhookEndpointService.java`
- `create(String ownerId, CreateEndpointRequest)` → `Created(endpoint, plaintextSecret)` —
  SSRF-validates, generates or accepts a secret, encrypts it.
- `list(String)`, `get(String, UUID)` — throws `ResourceNotFoundException`.
- `setActive(String, UUID, boolean)` — affects **future** events only. Deliveries already queued
  keep being retried: "stop sending me new things" and "throw away what you already promised to
  deliver" are different requests.
- `delete(String, UUID)` — soft.

#### `EventFanoutWriter.java`
- `writeAndFanOut(...)` → `Written(event, deliveries)` — `REQUIRES_NEW`. `saveAndFlush` so a
  duplicate idempotency key surfaces *inside* this call rather than at an arbitrary later flush
  point. The event and its deliveries are one transaction, so there is no instant at which an event
  exists with no deliveries queued.

#### `EventIngestionService.java`
- `ingest(String ownerId, IngestEventRequest, String idempotencyKey, String sourceIp)` →
  `IngestResult(event, deliveries, idempotentReplay)`. **Not** `@Transactional` — the transaction is
  one level down so this method can survive its rollback and recover.
- `findExisting`, `serialisePayload` (256KB cap), `normalise` — private.

#### `DeliveryService.java`
- `list(String, DeliveryStatus, int, int)` → `PageResponse<DeliveryResponse>`.
- `detail(String, UUID)` → `DeliveryDetailResponse` — state, timeline, endpoint and payload in one
  request, because the page is useless without all three and three round trips would mean three
  loading states.
- `forEvent(String, UUID)`.
- `replay(String, UUID)` — terminal only; `ConflictException` otherwise.
- `replayAll(String, List<UUID>)` — skips ineligible ids rather than failing the batch.
- `enrich(List<Delivery>)` — private. Resolves endpoint names and event types in **two batch reads**
  rather than one query per row. A page of 20 would otherwise be 41 queries.

#### `EventQueryService.java`
- `list(String, String eventType, int, int)` → `PageResponse<EventResponse>`. Deliberately omits
  deliveries from list items (that would be an N+1).
- `get(String, UUID)` → `EventResponse` with its fan-out.

#### `StatsService.java`
- `forOwner(String)` → `StatsResponse`. Four aggregate queries. Success rate counts only *terminal*
  deliveries; including pending ones would make the number dip every time traffic arrives.
- `throughput(String)` — private. Groups over `delivery_attempts`, not `deliveries`, because a
  delivery retried four times contributed four events at four different moments. Pins `date_trunc`
  to UTC and generates the Java series in UTC so the two agree by construction. Fills empty hours
  with zeros, so a five-hour outage does not render as a gentle slope.

### `dev.retryx.web`

#### Controllers
- **`WebhookEndpointController`** — `POST /api/webhooks` (201 with the show-once secret), `GET`,
  `GET /{id}`, `PUT /{id}/active`, `DELETE /{id}`.
- **`EventController`** — `POST /api/events` (201 new, 200 + `X-Idempotent-Replay: true` for a
  duplicate), `GET`, `GET /{id}`, `GET /{id}/deliveries`. `callerAddress` reads
  `X-Forwarded-For` but trusts it for nothing — it is client-supplied and trivially forged, so it is
  a debugging hint only.
- **`DeliveryController`** — `GET /api/deliveries?status=`, `GET /{id}`, `POST /{id}/replay`,
  `POST /replay`.
- **`StatsController`** — `GET /api/stats`.

#### `GlobalExceptionHandler.java`
One handler per exception type, mapping to the single error shape. `onUnexpected` returns a fixed
string plus a trace id and logs the stack trace server-side: an exception message can carry a SQL
fragment, a file path or a connection string, all of which tell an attacker about the inside of the
system and tell the caller nothing actionable.

#### DTOs (`dev.retryx.web.dto`)
`CreateEndpointRequest`, `EndpointResponse` (no secret field, by construction),
`CreatedEndpointResponse` (the only place the plaintext secret appears), `IngestEventRequest`,
`EventResponse`, `DeliveryResponse`, `AttemptResponse`, `DeliveryDetailResponse`, `PageResponse<T>`,
`StatsResponse`, `ErrorResponse`.

### `dev.retryx.bootstrap`

- **`StartupSafetyValidator`** — `onApplicationEvent(ApplicationReadyEvent)`. Refuses to start in
  prod with the published dev key or the SSRF guard off.
- **`DemoDataSeeder`** — `run(ApplicationArguments)`. Seeds one owner, one key and two endpoints
  (one healthy, one failing ~40%). Guarded by `retryx.demo.enabled` and an existence check.

### `receiver`

- **`ReceiverApplication`** — entry point.
- **`SignatureVerifier`** — `verify(String signatureHeader, byte[] body)` → `Result`
  (`VALID` / `NOT_CONFIGURED` / `MALFORMED` / `STALE` / `MISMATCH`). The reference implementation a
  consumer copies. Written independently of the sender's code on purpose.
- **`ReceiverStats`** — `record(eventId, deliveryId, attempt)`, `recordInjectedFailure()`,
  `recordSignature(Result)`, `reset()`, `snapshot()`, plus accessors. Tracks
  `deliveryId:attemptNumber` because *that* is the unit that must never repeat; event-id repeats are
  retries and are expected. Bounded at 500k keys with a `trackingSaturated` flag, so the figures say
  "accurate up to here" instead of quietly becoming wrong.
- **`WebhookController`** — `POST /hooks`, `/hooks/flaky` (40% failure), `/hooks/always-fail`,
  `/hooks/not-found` (404, the permanent branch), `/hooks/slow` (20s, longer than the read timeout),
  `GET /stats`, `POST /reset`, `GET /health`. Every handler takes `@RequestBody byte[]` — the only
  way to verify an HMAC.

### `frontend`

#### `src/lib`
- **`types.ts`** — hand-written mirror of the backend DTOs, so a breaking change shows up as a
  TypeScript error in the component that reads the field.
- **`api.ts`** — `RetryxApiError`, `apiKeyStore` (get/set/clear, with the localStorage trade-off
  documented), `request<T>` (attaches the key in one place, normalises every error), and the `api`
  object with one method per endpoint.
- **`format.ts`** — `duration`, `relativeTime` (handles future times, because a pending delivery's
  most important property is when it will next be tried), `absoluteTime`, `hourLabel`, `count`,
  `shortId` (keeps head *and* tail, because UUID prefixes are often shared), `prettyJson`,
  `compactJson`.

#### `src/components`
- **`StatusMark.tsx`** — `STATUS_PRESENTATION` and `OUTCOME_PRESENTATION` (the visual vocabulary,
  defined once), `StatusMark`, `OutcomeMark`, `StatusCode`. Status is a dot plus a word, never a
  filled pill: colour alone fails for about one in twelve men.
- **`AttemptRail.tsx`** — **the one bold element.** The attempt timeline drawn to scale, so the
  exponential backoff is visible. Switches from a linear to a square-root axis when the gap ratio
  exceeds 12, *and says so* — a silent log axis turns a chart of time into a chart of something
  else.
- **`ThroughputChart.tsx`** — hand-rolled SVG stacked columns. Retries are their own band, because a
  healthy system with a flaky receiver has a tall amber band *and* a high success rate, and folding
  them together hides exactly that.
- **`StatusStrip.tsx`** — the KPI band. Hairline-separated segments, not cards: the figures are one
  readout of one system, not separate things.
- **`NavRail.tsx`** — navigation plus live queue telemetry pinned at the foot, so depth and oldest
  wait are in peripheral vision on every page.
- **`AppShell.tsx`**, **`Primitives.tsx`** (`Spinner`, `SkeletonRows`, `Empty`, `ErrorState`,
  `PrimaryButton`, `GhostButton`), **`CopyButton.tsx`** (`CopyButton`, `IdChip`),
  **`JsonViewer.tsx`**, **`ThemeToggle.tsx`**.

#### `src/pages`
- **`Connect.tsx`** — API key entry. Verifies before storing, so a bad key errors next to the field
  rather than dropping the user into a broken dashboard.
- **`Overview.tsx`** — figures, 24-hour shape, then "needs attention" with bulk replay.
- **`Deliveries.tsx`** — deliveries and events, status filters, selection, bulk replay, pagination.
- **`DeliveryDetail.tsx`** — state band, the attempt rail, every attempt in full, endpoint context
  and the payload. Polls while the delivery is active and stops when it is terminal.

#### `src/index.css`
The design tokens. Every colour is a token swapped on `[data-theme]`, so no component uses a `dark:`
variant and light mode is the same code path rather than a parallel set of utilities.

---

## 8. Testing

### Unit tests — `mvn test`, no Docker

| Test | What it pins down |
| --- | --- |
| `BackoffPolicyTest` | The series doubles, caps, never regresses, does not overflow on an absurd attempt number; each jitter strategy's exact bounds with the random source pinned to 0.0 and 1.0 |
| `HmacSignerTest` | A known-answer test pinning the wire format; the timestamp and every body byte are covered; stale and future-skewed signatures are rejected; malformed headers do not throw; unknown scheme versions are tolerated |
| `RetryClassifierTest` | Every status class and transport failure, table-driven; nested and circular cause chains |
| `AesGcmSecretCipherTest` | Round trip; **500 encryptions produce 500 distinct nonces**; tampering is detected; wrong key fails; bad key lengths fail at startup |
| `SsrfGuardTest` | Every bypass: schemes, userinfo, ports, internal names, private ranges, and IPv4 smuggled inside IPv4-mapped/NAT64/6to4/Teredo IPv6 |
| `ApiKeyHasherTest` | Key shape, uniqueness over 1000 draws, hash stability, the display prefix is not usable |
| `EventEnvelopeWriterTest` | **Byte-identical across attempts**; the payload is not reformatted; awkward characters are escaped |

### Integration tests — `mvn verify`, needs Docker

Real PostgreSQL via Testcontainers, started once (`static` container). **Not H2** — the thing being
tested does not exist in H2: `FOR UPDATE SKIP LOCKED`, `jsonb`, partial unique indexes,
`count(*) FILTER`, `percentile_cont`. A suite that passes against H2 has tested the wrong database
and reported a green tick for it.

Schema creation goes through Flyway exactly as in production, so the migration is under test on
every run. `ddl-auto` is `none` rather than `validate`: the schema uses Postgres `text` where
Hibernate expects `varchar(255)` for a String field, which are the same type to Postgres and
different strings to Hibernate's validator — so `validate` fails at startup on a schema that is
correct. The mapping is checked by these tests instead, which run every query against the real
migrated schema, which catches the same class of mistake with fewer false positives.

`AbstractIntegrationTest` resets with `TRUNCATE … CASCADE`, **not** `@Transactional` rollback: a
rolled-back test transaction is invisible to any other connection, so the worker — on its own
connection, as in production — would see an empty table.

| Test | What it proves |
| --- | --- |
| `MultiWorkerClaimIT` | **Eight threads released simultaneously on 400 rows: every row claimed exactly once, no duplicates.** Plus lease stamping from the DB clock, backoff schedule respected, locked rows skipped not blocked on, oldest-first ordering, and the join that loads a job |
| `StaleClaimRecoveryIT` | A crashed worker's rows are stuck (invisible to the claim query), then recovered; live claims are not stolen; the reason is recorded; **no attempt is charged**, because Retryx cannot know whether the send happened |
| `DeliveryLifecycleIT` | Against WireMock on a real socket: happy path; the documented headers with a signature that **verifies against the bytes the receiver got**; 500→503→200 producing attempts 1,2,3 with the full history kept; budget exhaustion at exactly 4 attempts; a 404 stopping after one; a redirect not followed; a read timeout classified retryable; a private destination blocked at send time and recorded as `BLOCKED`; retries sending the same event id and the same body |
| `EventIngestionIT` | Fan-out skips disabled endpoints; a duplicate key returns 200 with the original id and **no second fan-out**; **twelve concurrent duplicates all succeed and produce exactly one event**; keys are scoped per owner; validation; authentication |
| `ReplayAndScopingIT` | Replay requeues and **extends** the budget while keeping history; an active delivery is a 409; bulk replay skips what it cannot do; every read and write endpoint returns 404 across owners; lists and stats are scoped; a revoked key stops working; a deleted endpoint still renders in history; the SSRF guard rejects at registration; the secret is shown exactly once |

Integration tests push the scheduler ten minutes out (`initial-delay-ms: 600000`) and call
`runOnce()` / `recoverOnce()` themselves. Driving a queue from a real scheduler is how a suite
acquires intermittent failures: the assertion races the poll, passes locally, fails in CI.

### Load test — `make load`

`load/k6-delivery.js`. Uses `ramping-arrival-rate`, not VU-based load: a VU-based test with a slow
API accidentally reduces its own offered load, so the system under test decides how hard it is being
pushed, which measures nothing.

Asserts correctness (zero duplicate sends, ingest p95 < 400ms) and *reports* throughput. Baking a
throughput figure measured on one laptop into a pass/fail check produces a test that fails on other
hardware for no useful reason.

---

## 9. Failure modes and operations

### What breaks, and what happens

| Failure | Behaviour |
| --- | --- |
| Receiver returns 500 | Retried with backoff until the budget is spent, then `FAILED` and replayable |
| Receiver returns 404 | One attempt, then `FAILED`. Not retried |
| Receiver times out | Retryable. The lease outlives the HTTP call, so the row is not stolen mid-send |
| Receiver accepts then never answers | The request timeout fires. Without one, the worker thread would park forever |
| Receiver processes the event but the response is lost | **The event is delivered twice.** Unavoidable; see [§11](#what-if-the-receiver-processes-the-event-but-the-response-is-lost) |
| Worker is `SIGKILL`ed mid-send | Lease expires, reaper requeues within `lease + interval`. May duplicate |
| Worker is `SIGTERM`ed | Graceful shutdown drains in-flight sends; no duplicates |
| Postgres is down | Polls fail and are logged; the worker retries next tick. No work is lost |
| Endpoint's DNS now resolves privately | Blocked at send time, recorded as a `BLOCKED` attempt with the reason |
| Encryption key changed | Secrets cannot be decrypted; attempts fail as `PERMANENT_ERROR` with "could not decrypt signing secret". Sending unsigned would be worse |
| Two workers share a `RETRYX_WORKER_ID` | Each can pass the other's lease check. **Duplicate sends.** Always set it per process |
| `lease-seconds` < HTTP timeout | A slow receiver outlives its claim, the reaper requeues it, and `retryx.lease.lost` climbs |

### Metrics

`/actuator/metrics`:

| Metric | Read it as |
| --- | --- |
| `retryx.deliveries.attempted{outcome}` | Attempt volume by verdict |
| `retryx.deliveries.delivered` | Successes |
| `retryx.deliveries.retried` | Retries scheduled. High *and* a high success rate is healthy |
| `retryx.deliveries.failed{reason}` | `budget_exhausted` / `permanent_error` / `blocked` |
| `retryx.claims.recovered` | Crashed-worker recovery. Non-zero means workers are dying |
| **`retryx.lease.lost`** | **Should be 0.** Non-zero means `lease-seconds` is too short for the configured HTTP timeouts, and Retryx is knowingly double-sending |
| `retryx.delivery.duration` | Receiver response time |

### Sizing

- **Connection pool > worker concurrency**, with headroom for the web tier. 16 in-flight sends
  against a 20-connection pool leaves 4 for the API — and the sends only hold a connection for the
  two short transactions, which is the whole point of §4.
- **`lease-seconds` > connect + read timeout**, with margin. Default 60s against 13s.
- **Scaling delivery** = more worker processes. `SKIP LOCKED` needs no configuration for this.
- **Scaling ingestion** = more API processes behind a load balancer. Stateless, no sticky sessions.

### Operational gaps

- **No retention policy.** `delivery_attempts` grows without bound. A real deployment needs
  partitioning by month or a scheduled delete.
- **No poison-pill protection.** A row that reliably crashes its worker is requeued forever without
  consuming budget, because the reaper deliberately charges no attempt. Bounding it needs a separate
  lease-expiry counter.
- **Offset pagination** gets slower at depth (`OFFSET 10000` makes Postgres walk and discard 10,000
  rows). Fine for a dashboard; keyset pagination is the fix for a machine-consumed API.

---

## 10. What is deliberately missing

Each of these was left out so the parts that are here could be done properly.

| Not here | Why, and what adding it involves |
| --- | --- |
| **Secret rotation** | Needs two live secrets per endpoint and a signature header carrying both, so receivers can migrate without a flag day. The `v1=` label in the signature scheme is the hook for it |
| **Rate limiting** | Per-owner ingestion quotas and per-endpoint delivery throttling. The latter is the interesting one: it needs a token bucket consulted *during* claiming, which means the claim query gets a join |
| **Event-type subscriptions** | Every active endpoint receives every event. Adding filters means an `event_types` column and a predicate in the fan-out query |
| **Multi-user accounts** | Keys map to an owner string. No signup, no key-minting endpoint (a public one would be worse than no auth), no roles |
| **KMS / envelope encryption** | The AES key comes from an environment variable. A KMS would give rotation and remove the key from the process environment |
| **Prometheus / Grafana** | Counters exist at `/actuator/metrics`; nothing scrapes them |
| **A message broker** | See [§11](#why-skip-locked-and-not-kafkarabbitmqsqs). At this scale nothing is missing without one |
| **`LISTEN`/`NOTIFY`** | Would cut idle latency below the 500ms poll, but it is not durable — a notification sent while a worker reconnects is gone. It can only ever be an optimisation on top of polling, never a replacement |
| **DNS pinning** | The remaining SSRF gap. Needs a custom connection factory that connects to the validated IP with the original `Host` header and SNI |
| **Retention / archival** | `delivery_attempts` grows forever |

---

## 11. Interview questions and answers

The questions this project is designed to be able to answer, with the answers.

### Why `SKIP LOCKED`, and not Kafka/RabbitMQ/SQS?

**The question behind the question** is whether you understand what a broker buys and what it costs.

Several worker processes poll one table and must never hand the same row to two senders. The
options:

- **Plain `SELECT … FOR UPDATE`** — correct, and serial. Worker B's query *blocks* on the rows
  worker A locked, waits for A's transaction to finish, then re-reads them and finds them taken.
  Throughput does not improve as you add workers; it degrades, because every worker queues behind
  the same hot rows.
- **`SELECT` then `UPDATE … WHERE status = 'PENDING'`** and trust the affected-row count. This
  actually works, but every loser of a race has done a wasted round trip, and under contention most
  attempts are losers.
- **Advisory locks** — one lock per row id. Works, but the locks live outside the table, so a crash
  analysis has to correlate two sources of truth, and `pg_locks` is not a queue.
- **A broker** — the right answer at a different scale.

`SKIP LOCKED` tells Postgres: lock what you can, step over anything already locked, never block.
Each worker gets a disjoint batch on the first attempt, and throughput scales with workers until the
database or the receivers saturate.

**Why not a broker, specifically:** the delivery state and the queue state would live in two systems
that can disagree. "Which deliveries are pending?" becomes a question you cannot answer with a query
— you'd have to consult Kafka for the queue and Postgres for the state, and reconcile them. Here the
answer is `SELECT count(*) FROM deliveries WHERE status = 'PENDING'`. Add the operational cost of
running a broker, consumer-group rebalancing, and the fact that Kafka's partition count caps your
consumer parallelism, and at this scale the database wins on every axis that matters.

**When I'd switch:** when the claim query's contention shows up in `pg_stat_activity`, or when the
queue table's write volume starts competing with the application's reads for I/O. Concretely:
tens of thousands of deliveries per second, or a backlog large enough that the partial index no
longer fits in cache. Below that, a broker is complexity without a corresponding problem.

### Why jitter?

A receiver goes down for thirty seconds. Ten thousand deliveries fail at roughly the same moment.
With pure exponential backoff they all wait *exactly* 2s, then *exactly* 4s, then *exactly* 8s — and
arrive in ten-thousand-request spikes, in lockstep, forever. The receiver comes back up, is flattened
by the first synchronised wave, fails again, and the waves get *sharper* with each round, because
every failure re-synchronises the group.

That is a thundering herd, and retrying is what created it. The retry logic intended to improve
reliability has become the thing preventing recovery.

Jitter spreads the group out in time. The same ten thousand retries land smeared across the window
instead of stacked at its edge.

**Which jitter:** Retryx defaults to *equal* jitter — `exp/2 + random(0, exp/2)`. Full jitter
(`random(0, exp)`) spreads better and is what AWS recommends, but it can draw a near-zero delay,
which means sometimes retrying almost immediately against a server that is still down. Equal jitter
guarantees a minimum wait, so a struggling receiver always gets at least half the intended breathing
room. When the thing being protected is somebody else's server, the guaranteed floor matters more
than the last few percent of spread. There is a 250ms hard floor underneath both, so full jitter
cannot produce a tight loop.

### What happens if the receiver processes the event but the response is lost?

**The receiver sees the event twice, and that is unavoidable.**

Retryx cannot distinguish "they processed it and the 200 was lost on the way back" from "they never
got it". Both look identical from here: a request went out and no response came. So it retries — and
the receiver, which already committed the event, gets it again.

This is not an implementation gap that a better design closes. The receiver's commit and Retryx's
record of that commit are in two different systems with no shared transaction. Making them atomic
would require distributed transactions across an arbitrary third-party HTTP endpoint, which is not
something you can ask of a webhook consumer.

So Retryx is honest about it: **at-least-once, documented, never claimed as exactly-once.** What it
does instead is make the duplicate cheap to handle:

- `X-Retryx-Event-Id` is **stable across every retry and every replay**
- the request body is **byte-identical** across retries, so a body hash works as a dedupe key too
- `Idempotency-Key` mirrors the event id, so receivers that already honour that convention get
  deduplication for free

The receiver's side of the contract is three lines:

```python
if already_processed(event_id):
    return "", 200          # 2xx so Retryx stops
```

The composite — at-least-once delivery plus an idempotent consumer — is *effectively* exactly-once
processing. That is the only form of exactly-once that exists.

### How is SSRF blocked?

Retryx takes a URL from a user and makes a request to it from inside the deployment's network. That
is the product, and it is also a textbook SSRF primitive: register
`http://169.254.169.254/latest/meta-data/iam/security-credentials/`, send yourself an event, and read
the cloud instance's IAM credentials off the delivery-detail page.

Seven layers, because any single one of them has a bypass:

1. **Scheme allowlist** — http/https only. `gopher://` can write arbitrary bytes to a TCP port,
   which turns SSRF into "run commands on your Redis".
2. **No credentials in the URL** — `http://expected@actual/` parses as two different hosts depending
   on which library you ask.
3. **Port allowlist** — removes the port scanner.
4. **Hostname denylist** — `localhost`, `*.internal`, `*.local`, the cloud metadata names.
5. **Resolve and check *every* address** — a hostile host publishes one public A record and one at
   `127.0.0.1`. Checking only the first answer means losing a coin flip. Loopback, link-local
   (covering the metadata address), private, CGNAT, multicast and reserved ranges all rejected —
   **including when smuggled inside IPv6**: `::ffff:127.0.0.1`, NAT64, 6to4 and Teredo each defeat
   an IPv4-only check.
6. **No redirects** — without this, every layer above is bypassed by a public URL answering
   `302 Location: http://169.254.169.254/`. This is the one people forget.
7. **Validate again before every send** — DNS changes between registration and attempt 6.

**The gap I'd name before being asked:** DNS rebinding. There is still a window between resolving and
connecting, and nothing stops the resolver returning a different answer for the connect. Closing it
means pinning the validated IP and connecting to it with the original `Host` header and SNI.
`SsrfGuard.validate` already returns the resolved addresses so a pinning connection factory can use
them; the client does not yet pin. It is documented as a known gap rather than quietly ignored —
which is also the honest answer to "is this complete?".

### Why separate `deliveries` from `delivery_attempts`?

Because one is **state** and the other is **history**, and they have opposite requirements.

`deliveries` answers "where is this now?" — it is mutable, updated on every retry, and the queue
needs it to be cheap to update and cheap to index. `delivery_attempts` answers "what has happened to
it?" — it is append-only and must never lose anything.

Merge them and the fifth attempt overwrites the evidence from the first four. "It returned 502 twice
and then timed out" becomes unanswerable, which is exactly the question someone opens the dashboard
to ask.

There are three further consequences worth naming:

- **Index size.** The queue's hot index is a partial index on two live statuses. Terminal rows —
  eventually the overwhelming majority — stay out of it, so index size tracks backlog depth rather
  than total history. A merged table couldn't do that.
- **Attempt numbers stay unique.** `UNIQUE (delivery_id, attempt_number)` is both a correctness
  invariant and a safety net: if two workers ever did send the same attempt, the second insert fails
  loudly instead of silently duplicating.
- **Replay keeps the history.** `replay()` *extends* `max_attempts` rather than resetting
  `attempt_count`, so a twice-replayed delivery reads 1-6, 7-12 and the original failures remain
  visible beside the retry.

### Why is there no transaction open during the HTTP call?

Because holding one fails in four ways simultaneously:

1. a pool connection is pinned for the duration of **someone else's** slow server
2. the claimed rows stay locked, so `SKIP LOCKED` can no longer route around them — the mechanism
   that makes multi-worker claiming fast is defeated by the thing that was supposed to use it
3. Postgres cannot vacuum anything newer than the oldest open transaction, so a dead receiver causes
   table bloat
4. 20 concurrent sends to a dead receiver exhaust a 20-connection pool, and the API goes down with
   the receiver

The last one is the one that gets you paged. And the instinct — "increase the pool size" — makes it
worse, because the problem is duration, not count.

So: **claim, commit, send, record.** Two short transactions with a network call between them, never
inside one.

The structural consequence is that `DeliveryProcessor` and `DeliveryOutcomeRecorder` are separate
beans. `@Transactional` works through a proxy, and a proxy only intercepts calls from outside the
object — so `this.record(...)` on the same bean would silently run with **no transaction at all**,
turning the attempt insert and the delivery update into two autocommits. That bug is invisible in
review and the code looks correct.

### How is idempotency actually enforced?

**Not** by this, which is the answer most people give:

```java
if (!exists(key)) { insert(); }   // wrong under concurrency
```

Two simultaneous requests both run `exists`, both see nothing, and both insert. The window is small,
which only means the bug is rare, reproduces under load, and is impossible to diagnose from a stack
trace.

The actual guarantee is a **partial unique index**:

```sql
CREATE UNIQUE INDEX uq_events_owner_idempotency_key
    ON events (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

Postgres serialises the two inserts and one fails. `EventIngestionService` then *uses* that failure:
it catches the violation and re-reads the row the winner committed, so the loser returns the same
event id. The pre-check at the top of the method is purely an optimisation for the common case,
where the duplicate arrives seconds later and there is no race at all.

Three details that matter:

- **Scoped to `(owner_id, idempotency_key)`** — two tenants may legitimately both use `"order-1"`,
  and a global constraint would have one tenant's traffic deduplicating against another's.
- **Partial** — rows without a key are not forced to be unique.
- **A duplicate returns 200, not 409.** The producer asked for "this event exists", and after the
  duplicate it does. Returning an error would force every well-behaved retrying client to handle a
  failure that is not one. `X-Idempotent-Replay: true` is there for clients that want to tell the
  difference.

`EventIngestionIT` fires twelve concurrent requests with the same key and asserts all twelve succeed
and exactly one event exists.

### How does crash recovery work, and how long does it take?

A worker claims 25 rows, flips them to `IN_PROGRESS`, and is `SIGKILL`ed. Those rows are now marked
as being worked on by a process that no longer exists, and nothing in the claim query will return
them again because it only looks at `PENDING`. **Without recovery they are lost permanently and
silently** — no error anywhere, the dashboard shows them as "sending" forever, and the events never
arrive. This is the most common bug in hand-rolled database queues.

The lease is what makes it detectable. The claim stamped `locked_until = now() + lease`, on the
**database** clock. A live worker finishes well inside that window, so "`IN_PROGRESS` and
`locked_until < now()`" means unambiguously that whoever held this row is not coming back.

Recovery time is bounded by **`lease-seconds + reaper interval`** — 65 seconds with the defaults, and
a tuning parameter rather than an accident.

Two details:

- **The reaper itself uses `SKIP LOCKED`**, so every worker can run its own without a leader election
  or a separate cron deployment.
- **It deliberately does not charge an attempt.** The worker may have died before sending, after
  sending, or mid-response, and Retryx cannot tell which — so it does not pretend to by logging an
  attempt that may never have happened, or by eating a retry the delivery may still need. The honest
  consequence is a possible duplicate send, which is why every webhook carries a stable event id.

**The follow-up to be ready for:** "so a row that always crashes its worker loops forever?" Yes —
that is a poison pill, and bounding it needs a separate lease-expiry counter, which is listed as
future work rather than pretended away.

### Why does the lease use the database clock but backoff use the application clock?

Because they are different kinds of value and the asymmetry is deliberate.

A **lease** is distributed mutual exclusion. The only way several machines can agree on when it
expires is to read the time from one shared place. If worker A's clock ran two minutes fast, it would
consider its own lease expired early and happily re-send another worker's in-flight delivery — a
duplicate caused purely by clock skew, and essentially undiagnosable.

**Backoff** is "try again in about eight seconds". A few milliseconds of skew changes nothing.

So `locked_until` is `now() + lease` evaluated by Postgres, and `next_attempt_at` is computed in
Java. Noticing that one of the two needs a shared clock and the other does not is the point.

### What are the retry rules, and why?

| Status | Verdict | Why |
| --- | --- | --- |
| 2xx | success | Anything 2xx — receivers legitimately answer 200/201/202/204 |
| 408, 425, 429 | retry | The receiver is explicitly asking for a retry or a slow-down |
| 5xx | retry | The receiver is broken, not the request |
| other 4xx | **no retry** | A statement about the request, which will be byte-identical next time |
| 3xx | **no retry** | We don't follow redirects, so a redirect is a configuration error |
| timeout / refused / reset | retry | The definition of transient |
| TLS handshake failure | **no retry** | An expired certificate needs an operator, not another attempt in 30 seconds |
| `UnknownHostException` | retry | Looks permanent, but resolver blips are common |

Both ways of getting this wrong are expensive, which is the real answer. Retry something permanent
and a single misconfigured endpoint becomes a self-inflicted load test — six attempts per event,
multiplied by every event. Fail something transient and you have silently dropped a customer's event
because a load balancer hiccuped for 200ms.

The judgement calls are where the thinking shows: `UnknownHostException` is retried because the
budget bounds the cost of being wrong at six attempts, while being wrong the other way drops data.
An unexpected exception from Retryx's *own* code is retried for the same reason — the conservative
reading is "our bug, not their rejection".

### Why sign the body bytes rather than a serialised object?

Because a signature over a body that was regenerated between signing and sending is a signature over
something *else*.

If the signer parses JSON into an object and the sender re-serialises it, key order, whitespace,
number formatting (`1.0` vs `1`) and unicode escaping are all at the serialiser's mercy. The two
byte strings differ, the HMAC differs, and every receiver rejects the traffic — and the bug is
invisible until production, because both sides compute "the same" signature over "the same" JSON.

So `HmacSigner.sign` takes a `byte[]`, which forces the caller to keep the bytes it signed and send
exactly those. `WebhookSender` builds the body once into a local, signs it, and hands that same array
to `BodyPublishers.ofByteArray`.

`EventEnvelopeWriter` goes further and never parses the payload at all: it comes out of `jsonb` as
text and is spliced in as raw JSON. Postgres normalises `jsonb` on write, so the text is already
canonical — that normalisation happening *once, at ingest* is what makes splicing safe, and it also
means no JSON tree is allocated per attempt on the hot path.

### Why is the timestamp inside the MAC?

Without it, the signature proves only that the message came from someone holding the secret — not
that it is *recent*. Anyone who captured one valid request can replay it forever, and every replay
verifies perfectly.

With the timestamp covered by the MAC, the receiver enforces a freshness window (Retryx suggests five
minutes) and stale captures are rejected. **Covered** is the operative word: a timestamp sent
*alongside* an unrelated signature is just a number an attacker edits.

The `v1=` label is there so a second algorithm can be added later and receivers can accept both
during the migration, instead of a flag day. The reference verifier ignores unknown versions for
exactly that reason.

### Why SHA-256 for API keys instead of bcrypt?

Because a slow KDF solves a problem these keys do not have.

bcrypt exists to make *guessing* expensive. Users pick "hunter2", so you make each guess cost 100ms
and brute force becomes impractical. These keys are 24 bytes of `SecureRandom` — 192 bits. There is
nothing to guess at any cost per guess.

What a slow hash *would* buy is 100ms of CPU on the authentication path of **every single API
request**: a denial of service you built yourself, and one that gets worse under exactly the load
where you need headroom.

The property that actually matters is still preserved: a stolen database yields hashes, not usable
keys.

**The follow-up:** "what about timing attacks on the comparison?" There is no comparison. The
presented key is hashed and looked up by that hash on a unique index — a wrong key finds nothing, and
there is no byte-by-byte compare whose duration could leak anything. (Where Retryx *does* compare
secrets — HMAC verification — it uses `MessageDigest.isEqual`, because `String.equals` short-circuits
at the first differing byte and leaks through timing how much of a forged signature was correct.)

### Why AES-GCM, and what is the nonce rule?

**GCM** because it is authenticated encryption. The 128-bit tag means a tampered ciphertext fails to
decrypt rather than yielding attacker-influenced plaintext. With unauthenticated CBC, someone with
write access to the database could flip bits in a stored secret and observe the effect.

**The nonce rule:** a fresh 12-byte `SecureRandom` nonce per encryption, stored alongside the
ciphertext. Reusing a nonce under the same key does not *weaken* GCM, it **breaks** it — two messages
under one nonce leak their XOR and allow recovery of the authentication subkey, after which an
attacker can forge tags at will. So the nonce is never derived from the record, never an
application-level counter, never zero. `AesGcmSecretCipherTest` asserts 500 encryptions produce 500
distinct nonces.

**What it protects and what it doesn't:** the key comes from an environment variable — outside the
database — so this defends against a stolen dump, a leaked backup or a read-only SQL injection. It
does **not** defend against someone who already has the running process's environment. That needs a
KMS, which is listed as future work rather than implied.

### Why poll instead of `LISTEN`/`NOTIFY`?

The objection to polling is that it wastes queries. At 500ms that is two index lookups a second
against a partial index sized to the backlog — unmeasurable next to the HTTP calls this system exists
to make.

What polling buys is that the **only** coordination mechanism is the database. No broker to run, no
consumer group to rebalance, no listener to reconnect, and a worker that was down for an hour catches
up by doing nothing special.

`LISTEN`/`NOTIFY` would cut idle latency below 500ms, but **it is not durable**: a notification sent
while a worker is reconnecting is simply gone. So it can only ever be an optimisation layered on top
of polling, never a replacement for it. Retryx keeps the reliable half; adding the fast half is a
latency optimisation, not a correctness one.

### Why `fixedDelay` and not `fixedRate`?

`fixedRate` starts a new poll every N milliseconds regardless of whether the previous one finished.
With sends that can take thirteen seconds, polls pile up — each claiming another batch — and the
process quietly takes on unbounded concurrent work until it falls over.

`fixedDelay` waits for the previous batch. In-flight work is bounded by `batch-size`, and the loop
becomes natural backpressure: slow receivers slow the claim rate instead of exploding memory.

Related: the `@Scheduled` task scheduler is configured with **two** threads, not Spring's default of
one. On one thread the reaper could only run between the worker's batches — precisely when the system
is least healthy and recovery matters most.

### How would you scale this?

- **Delivery throughput** — run more worker processes. `SKIP LOCKED` guarantees disjoint batches, so
  there is no partition assignment, no leader election and no configuration. This is the payoff for
  putting the queue in the database.
- **Ingestion** — more API processes behind a load balancer. Stateless, no sticky sessions.
- **The first real bottleneck** is the connection pool, not the database. Worker concurrency must sit
  below pool size with headroom for the web tier — and the reason that works at all is that sends
  hold no connection.
- **Then** the `deliveries` table's write volume. Partition by `created_at`, or move terminal rows to
  an archive table so the live table stays small.
- **Then** read replicas for the dashboard aggregates, which are the only genuinely expensive
  queries.

**What I'd measure first:** `oldestDueAgeSeconds`, not queue depth. Depth can be large and perfectly
healthy; a *rising* oldest-age is the signal that workers are falling behind.

### What would you do differently with more time?

In priority order:

1. **DNS pinning** — the remaining SSRF gap, and the only outstanding security issue.
2. **Secret rotation** — two live secrets per endpoint with both signatures in the header, so
   receivers migrate without a flag day. The `v1=` scheme label is already the hook.
3. **Retention** — `delivery_attempts` grows without bound. Monthly partitions, dropped on a
   schedule.
4. **Per-endpoint rate limiting** — the interesting one, because it has to be enforced *during*
   claiming or it does not work. A token bucket joined into the claim query.
5. **Poison-pill protection** — a lease-expiry counter, so a row that reliably kills its worker
   eventually fails instead of looping.
6. **Keyset pagination** for the list APIs — `OFFSET 10000` makes Postgres walk and discard 10,000
   rows.

### What is the hardest bug you had to reason about here?

The one that does not throw: `this.record(...)` on the same bean as `process()`.

`@Transactional` is proxy-based, so a self-invocation bypasses the proxy entirely and the method runs
with **no transaction**. The attempt insert and the delivery update become two separate autocommits,
and a crash between them leaves an attempt logged against a delivery still marked `IN_PROGRESS` —
which the reaper then requeues, producing a duplicate send and a timeline with a gap in it.

Nothing fails. No exception, no log line, no failing test unless you happen to kill the process in a
two-millisecond window. The code reads correctly. The fix is structural — the two methods live on
different beans so the call crosses a proxy boundary — and the reasoning is written into the Javadoc
of `DeliveryOutcomeRecorder`, because the next person to "tidy up" those two classes into one would
reintroduce it.

The same shape appears in `EventIngestionService` → `EventFanoutWriter`, where catching the
constraint violation requires the failing transaction to have already rolled back.

