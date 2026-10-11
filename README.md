<div align="center">

# Retryx

**Your events, delivered to the URLs you register — and retried until they arrive.**

Accept an event once. Retryx fans it out to every endpoint you've registered, signs each request,
retries the failures with exponential backoff, records every attempt, and lets you replay anything
that gave up.

No message broker. PostgreSQL *is* the queue.

[Quick start](#quick-start) · [How it works](#how-it-works) · [API](#api) ·
[Receiving webhooks](#receiving-webhooks) · [Measured performance](#measured-performance) ·
[Developer guide](DEVELOPER.md)

</div>

---

## The problem

Sending a webhook is one line of code. Sending a webhook *reliably* is not.

The receiver will be down for ninety seconds during a deploy. Their load balancer will return a 502.
Their TLS certificate will expire on a Saturday. A network blip will swallow your request after they
processed it. Someone will register `http://169.254.169.254/latest/meta-data/` as a webhook URL and
read your cloud credentials out of the response.

Retryx is the piece that handles all of that, so the service producing events doesn't have to.

## What it does

|                            |                                                                                                                      |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Fan-out**                | One event in, one delivery per registered endpoint, created in the same transaction as the event                     |
| **Idempotent ingestion**   | Send the same `Idempotency-Key` twice and the second call returns the first event, enforced by a database constraint  |
| **Exponential backoff**    | 2s, 4s, 8s, 16s, 32s — with jitter, so a recovering receiver isn't flattened by synchronised retries                  |
| **Smart retry rules**      | 5xx, 429, 408 and timeouts retry; 404 and 422 don't. A misconfigured endpoint fails once, not six times              |
| **Signed requests**        | HMAC-SHA256 over the timestamp and the exact body bytes, so receivers can verify and reject replays                  |
| **Encrypted secrets**      | Signing secrets stored AES-256-GCM with a key from the environment, not the database                                 |
| **SSRF protection**        | Scheme, port and hostname allowlists, every resolved address checked, no redirects followed, re-validated before each send |
| **Safe multi-worker**      | `FOR UPDATE SKIP LOCKED` claiming. Run as many workers as you like; none will ever send another's delivery           |
| **Crash recovery**         | A killed worker's in-flight deliveries are requeued automatically within seconds                                     |
| **Full attempt history**   | Every attempt, with status, timing, the receiver's response and the signature sent                                   |
| **Replay**                 | Resend anything that failed, one at a time or in bulk, with the original history kept intact                         |

## Quick start

You need Docker. Nothing else.

```bash
docker compose up --build
```

Then open **http://localhost:5173** and click **Use the demo key**.

That starts five things: PostgreSQL, the API, a *second* worker process, a reference receiver, and
the dashboard. The second worker is there so the multi-worker claiming is something you can watch
rather than something you have to take on faith.

Two endpoints are seeded for you — one healthy, one that fails about 40% of requests — so there is
real retry traffic to look at within seconds.

### Send an event

```bash
curl -X POST http://localhost:8080/api/events \
  -H "X-Api-Key: rtx_sk_demo_0000000000000000000000" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-1" \
  -d '{"eventType":"order.created","payload":{"orderId":"A-1","total":42.50}}'
```

Send it a second time with the same `Idempotency-Key` and you'll get `200` with
`X-Idempotent-Replay: true` and the same event id — no second event, no duplicate webhooks.

### Watch a retry happen

The seeded "Analytics sink" endpoint fails roughly 40% of the time. Send a few events, open
**Deliveries**, and click one that's waiting. The attempt timeline shows the backoff gaps drawn to
scale — you can see the delay doubling.

### Prove there are no duplicate sends

```bash
curl -s http://localhost:9090/stats
```

`duplicateAttempts` counts how many times the same `(delivery, attempt)` pair arrived. With two
workers polling the same table, it stays at **0**. `repeatedEvents` will not be zero — those are
retries, and they are the point.

## Running it without Docker

You need **Node 20+**, **Java 21** and **Maven**. Postgres can still come from Docker, or be a local
install.

One-time setup — checks your tools, installs dashboard dependencies and compiles both Java apps:

```bash
bash scripts/setup.sh
```

Or on Windows (PowerShell):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1
```

Or do it by hand:

```bash
cd frontend && npm install && cd ..
cd backend && mvn clean package -DskipTests && cd ..
cd receiver && mvn clean package -DskipTests && cd ..
```

Then run the four pieces in four terminals:

```bash
docker compose up -d postgres
```

```bash
cd receiver && mvn spring-boot:run
```

```bash
cd backend && mvn spring-boot:run
```

```bash
cd frontend && npm run dev
```

> **Windows without Docker:** You can launch all four components (embedded PostgreSQL on `:5432`, reference receiver on `:9090`, backend API on `:8080`, and frontend dev server on `:5173`) in one go by running:
> ```cmd
> scripts\start-all.bat
> ```

The dashboard comes up on **http://localhost:5173** and proxies `/api` to the backend on `:8080`, so
the browser only ever talks to one origin — the same shape as production.

To watch two workers share the queue, start a second backend with a different identity:

```bash
cd backend && RETRYX_PORT=8081 RETRYX_WORKER_ID=local-worker-2 RETRYX_DEMO_ENABLED=false mvn spring-boot:run
```

> `make` wraps all of these — `make help` lists the targets. It isn't installed on Windows by
> default, so the raw commands are above.


## How it works

```
                                     ┌──────────────────────────────┐
 POST /api/events ──────────────────► │ ingest                       │
   X-Api-Key                          │  • authenticate (hashed key) │
   Idempotency-Key                    │  • store event (jsonb)       │
                                      │  • queue 1 delivery per      │
                                      │    active endpoint           │
                                      └──────────────┬───────────────┘
                                                     │ one transaction
                                                     ▼
                              ┌────────────────────────────────────────┐
                              │  deliveries  ← THIS IS THE QUEUE       │
                              │  status, attempt_count,                │
                              │  next_attempt_at, locked_until         │
                              └───────┬──────────────────────┬─────────┘
                                      │ FOR UPDATE           │
                                      │ SKIP LOCKED          │
                      ┌───────────────▼──────┐   ┌───────────▼──────────┐
                      │ worker A             │   │ worker B             │
                      │  claim ─ commit      │   │  claim ─ commit      │
                      │  sign + POST         │   │  sign + POST         │
                      │  record outcome      │   │  record outcome      │
                      └───────────┬──────────┘   └───────────┬──────────┘
                                  │                          │
                                  ▼                          ▼
                       ┌──────────────────────┐   ┌──────────────────────┐
                       │ your endpoint        │   │ your other endpoint  │
                       │ X-Retryx-Signature   │   │ X-Retryx-Event-Id    │
                       └──────────────────────┘   └──────────────────────┘

        2xx  → DELIVERED
        5xx  → PENDING, next_attempt_at += backoff(attempt)   ──┐
        4xx  → FAILED (no retry)                                │ replayable
        budget exhausted → FAILED                             ──┘
        worker crashed   → lease expires → requeued by the reaper
```

Three ideas carry the design:

**The queue is a table.** Workers claim rows with `FOR UPDATE SKIP LOCKED`, which tells Postgres to
lock what it can and step over anything another worker already holds. Each worker gets a disjoint
batch on the first try, with no blocking, no broker, and no consumer-group rebalancing. Scaling
delivery throughput means starting another copy of the process.

**Backoff is a timestamp, not a sleep.** Retrying later means writing a further-future
`next_attempt_at` and letting the row fall out of the claim query's `WHERE`. No worker ever blocks
waiting for a retry window.

**No transaction is open during the HTTP call.** Claim, commit, *then* send, then record in a second
short transaction. Holding the transaction across a ten-second receiver timeout would pin a
connection, keep the row locks held, and take the API down with the receiver.

## API

Every request needs `X-Api-Key` (or `Authorization: Bearer`), and every query is scoped to the
key's owner.

| Method   | Path                              | What it does                                              |
| -------- | --------------------------------- | --------------------------------------------------------- |
| `POST`   | `/api/webhooks`                   | Register an endpoint. Returns the signing secret **once** |
| `GET`    | `/api/webhooks`                   | List your endpoints                                       |
| `GET`    | `/api/webhooks/{id}`              | One endpoint                                              |
| `PUT`    | `/api/webhooks/{id}/active?active=` | Enable or disable, keeping history                      |
| `DELETE` | `/api/webhooks/{id}`              | Soft-delete, keeping history                              |
| `POST`   | `/api/events`                     | Ingest an event. Honours `Idempotency-Key`                 |
| `GET`    | `/api/events`                     | List events, paginated                                    |
| `GET`    | `/api/events/{id}`                | One event, with its fan-out                               |
| `GET`    | `/api/events/{id}/deliveries`     | Deliveries created from one event                         |
| `GET`    | `/api/deliveries?status=`         | List deliveries, filtered by status                       |
| `GET`    | `/api/deliveries/{id}`            | One delivery with its full attempt timeline               |
| `POST`   | `/api/deliveries/{id}/replay`     | Send a failed or delivered webhook again                  |
| `POST`   | `/api/deliveries/replay`          | Replay several at once                                    |
| `GET`    | `/api/stats`                      | Dashboard figures                                         |

## Receiving webhooks

Every request carries these headers:

```
X-Retryx-Event-Id:    7a1c9f2d-…        stable across retries — deduplicate on this
X-Retryx-Delivery-Id: 3e4b8a71-…        stable across retries
X-Retryx-Event-Type:  order.created
X-Retryx-Attempt:     3                 the only header that changes between retries
X-Retryx-Timestamp:   1760000000
X-Retryx-Signature:   t=1760000000,v1=9f86d081884c7d659a2feaa0c55ad015…
Idempotency-Key:      7a1c9f2d-…        mirrors the event id
```

The body is the same bytes on every retry:

```json
{
  "id": "7a1c9f2d-…",
  "type": "order.created",
  "created_at": "2026-10-11T09:14:02Z",
  "data": { "orderId": "A-1", "total": 42.5 }
}
```

### Verifying a signature

```python
import hashlib, hmac, time

def verify(raw_body: bytes, signature_header: str, secret: str, tolerance=300) -> bool:
    parts = dict(p.split("=", 1) for p in signature_header.split(","))
    timestamp, provided = int(parts["t"]), parts["v1"]

    # Reject old captures. Without this, anyone who recorded one valid request
    # can replay it forever and it will verify perfectly every time.
    if abs(time.time() - timestamp) > tolerance:
        return False

    expected = hmac.new(
        secret.encode(),
        f"{timestamp}.".encode() + raw_body,   # timestamp, a dot, then the RAW bytes
        hashlib.sha256,
    ).hexdigest()

    # Constant-time. `==` returns early at the first differing byte, which leaks
    # through timing how much of a forged signature was correct.
    return hmac.compare_digest(expected, provided)
```

**Use the raw body.** If your framework parses the JSON and you re-serialise it to verify, the bytes
won't match what was signed — key order, whitespace and number formatting will all differ — and
nothing will ever verify. `request.get_data()` in Flask, `express.raw()` in Express,
`@RequestBody byte[]` in Spring.

A full reference implementation, in Java, is in
[`receiver/src/main/java/dev/retryx/receiver/SignatureVerifier.java`](receiver/src/main/java/dev/retryx/receiver/SignatureVerifier.java).

### Delivery is at-least-once

**Retryx does not offer exactly-once delivery, and nothing can.** If your service commits an event
and the response is lost on the way back, Retryx cannot tell that apart from your service never
receiving it, so it retries — and you see the event twice.

What Retryx does instead is make the duplicate cheap to handle: `X-Retryx-Event-Id` is stable across
every retry and every replay, and the body is byte-identical. Record the ids you've processed and
ignore repeats.

```python
if already_processed(request.headers["X-Retryx-Event-Id"]):
    return "", 200          # 2xx, so Retryx stops retrying
```

Answer 2xx as soon as you've durably accepted the event, then do the work asynchronously. Holding
the connection open while you process means Retryx's read timeout fires, and you get retried for
work you already completed.

## Measured performance

Run it yourself:

```bash
make load      # 20% injected failures, then prints the figures
```

The load test reports deliveries/second, eventual success rate, duplicate sends, and receiver
latency percentiles. It asserts the things that must be true — zero duplicate sends, ingest p95
under 400ms — and *reports* throughput rather than asserting a number, because throughput depends
on the machine and a hardcoded figure would just fail on different hardware.

With 20% of attempts failing and a six-attempt budget, the chance of a delivery never succeeding is
0.2⁶ ≈ 0.0064%, so the eventual success rate should be above 99.9%. Anything lower means deliveries
are being lost somewhere other than to the injected failures.

> Fill in your own numbers here after running it. Quoting figures measured on someone else's
> hardware is how benchmarks become fiction.

## Tech

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Spring Data JPA with raw SQL for the queue · Flyway ·
React 19 · TypeScript · Vite · Tailwind CSS 4 · JUnit 5 · Testcontainers · WireMock · k6 · Docker

## Not included

Deliberately out of scope, so the parts that are here could be done properly:

- **Secret rotation** — one secret per endpoint; rotating means re-registering
- **Rate limiting** — no per-owner quotas on ingestion or per-endpoint delivery throttling
- **Event-type subscriptions** — every active endpoint receives every event
- **Multi-user accounts** — API keys map to an owner string; there is no signup, no UI for creating
  keys, and no roles
- **Key management beyond an environment variable** — no KMS, no envelope encryption, no rotation
- **Prometheus dashboards** — counters are exposed at `/actuator/metrics`, but nothing scrapes them
- **A message broker** — and at this scale, nothing is missing without one

The reasoning behind each, and what adding it would involve, is in
[DEVELOPER.md](DEVELOPER.md#what-is-deliberately-missing).

## Documentation

- **[DEVELOPER.md](DEVELOPER.md)** — architecture, every file and function, configuration reference,
  the failure modes, and the design decisions with their trade-offs
- **[backend/src/main/resources/db/migration/V1__initial_schema.sql](backend/src/main/resources/db/migration/V1__initial_schema.sql)**
  — the five tables, annotated

## Licence

MIT.
