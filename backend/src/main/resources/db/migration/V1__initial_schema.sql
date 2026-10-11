-- ============================================================================
--  Retryx — V1 initial schema
-- ============================================================================
--  Five tables:
--    api_keys           authentication + owner identity
--    webhook_endpoints   destinations, with an AES-GCM encrypted signing secret
--    events              what the producer sent us (immutable)
--    deliveries          the WORK QUEUE. Workers claim rows from here.
--    delivery_attempts   append-only audit log, one row per HTTP attempt
--
--  Design notes that matter (see DEVELOPER.md for the long version):
--    * deliveries is mutable state ("where is this delivery now?") while
--      delivery_attempts is an immutable log ("what has happened to it?").
--      Keeping them apart means a retry never destroys evidence.
--    * owner_id is denormalised onto events/deliveries so every owner-scoped
--      API query is a single-table index scan with no joins.
--    * timestamptz everywhere. Never store naive local time in a queue.
-- ============================================================================


-- ---------------------------------------------------------------------------
--  api_keys — we store only a SHA-256 hash of the key, never the key itself
-- ---------------------------------------------------------------------------
CREATE TABLE api_keys
(
    id           UUID        PRIMARY KEY,
    owner_id     TEXT        NOT NULL,
    -- SHA-256 (hex) of the raw key. The raw key is high-entropy and random,
    -- so a fast hash is correct here; bcrypt/argon2 exist to slow down
    -- guessing of LOW-entropy human passwords, which this is not.
    key_hash     TEXT        NOT NULL,
    -- First few characters of the raw key, kept in clear so the UI can show
    -- "rtx_sk_8fa2…" without us being able to reconstruct the secret.
    key_prefix   TEXT        NOT NULL,
    label        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

-- The authentication hot path: one unique-index lookup per request.
CREATE UNIQUE INDEX uq_api_keys_key_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_owner ON api_keys (owner_id);


-- ---------------------------------------------------------------------------
--  webhook_endpoints — where we deliver to
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_endpoints
(
    id               UUID        PRIMARY KEY,
    owner_id         TEXT        NOT NULL,
    name             TEXT        NOT NULL,
    url              TEXT        NOT NULL,
    -- AES-GCM ciphertext, base64url: v1.<nonce>.<ciphertext+tag>
    -- The key comes from the RETRYX_ENCRYPTION_KEY environment variable.
    encrypted_secret TEXT        NOT NULL,
    -- Non-reversible display hint for the UI, e.g. "whsec_1f4c…".
    secret_preview   TEXT        NOT NULL,
    description      TEXT,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    -- Per-endpoint retry budget. NULL means "use the server default".
    max_attempts     INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Soft delete: deliveries keep pointing at the endpoint so history and
    -- the delivery-detail page never show a dangling reference.
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT ck_endpoints_url_scheme CHECK (url ~* '^https?://')
);

-- Fan-out reads "all live endpoints for this owner" on every ingest, and the
-- dashboard reads the same set. A partial index keeps deleted rows out of it.
CREATE INDEX idx_endpoints_owner_live
    ON webhook_endpoints (owner_id, created_at DESC)
    WHERE deleted_at IS NULL;


-- ---------------------------------------------------------------------------
--  events — immutable record of what the producer handed us
-- ---------------------------------------------------------------------------
CREATE TABLE events
(
    id              UUID        PRIMARY KEY,
    owner_id        TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    -- Optional client-supplied key from the Idempotency-Key header.
    idempotency_key TEXT,
    source_ip       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency is scoped to the owner: two tenants may legitimately use the
-- same key. Partial, so rows without a key are not forced to be unique.
CREATE UNIQUE INDEX uq_events_owner_idempotency_key
    ON events (owner_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_events_owner_created ON events (owner_id, created_at DESC);
CREATE INDEX idx_events_owner_type ON events (owner_id, event_type);


-- ---------------------------------------------------------------------------
--  deliveries — THE QUEUE
-- ---------------------------------------------------------------------------
--  One row per (event, endpoint) pair. This is the only table workers mutate
--  while claiming work, and the only one touched by FOR UPDATE SKIP LOCKED.
--
--  Status machine:
--
--      PENDING ──claim──> IN_PROGRESS ──2xx──────────> DELIVERED
--         ^                    │
--         │                    ├──retryable──> PENDING (next_attempt_at += backoff)
--         │                    │
--         │                    ├──permanent / budget exhausted ──> FAILED
--         │                    │
--         └──lease expired─────┘   (StaleClaimReaper requeues crashed work)
--
--      FAILED / DELIVERED ──replay──> PENDING (budget extended)
-- ---------------------------------------------------------------------------
CREATE TABLE deliveries
(
    id               UUID        PRIMARY KEY,
    -- Denormalised from events so owner-scoped list queries need no join.
    owner_id         TEXT        NOT NULL,
    event_id         UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    endpoint_id      UUID        NOT NULL REFERENCES webhook_endpoints (id) ON DELETE CASCADE,

    status           TEXT        NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER     NOT NULL DEFAULT 0,
    -- Hard ceiling for this row. Replay raises it rather than resetting
    -- attempt_count, so attempt numbers in delivery_attempts stay unique
    -- and strictly increasing for the lifetime of the delivery.
    max_attempts     INTEGER     NOT NULL DEFAULT 6,

    -- When this row becomes claimable again. Backoff is implemented purely
    -- by pushing this timestamp into the future.
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Lease expiry. While status = IN_PROGRESS and locked_until > now() the
    -- row belongs to locked_by. Past that, any worker may reclaim it.
    locked_until     TIMESTAMPTZ,
    locked_by        TEXT,

    last_status_code INTEGER,
    last_error       TEXT,
    replay_count     INTEGER     NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_deliveries_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_deliveries_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0)
);

-- Fan-out is idempotent: re-running it for the same event cannot create a
-- second delivery to the same endpoint.
CREATE UNIQUE INDEX uq_deliveries_event_endpoint
    ON deliveries (event_id, endpoint_id);

-- *** The claim index. ***
-- The claim query is  WHERE status = 'PENDING' AND next_attempt_at <= now()
--                     ORDER BY next_attempt_at
-- A partial index on the two live statuses keeps DELIVERED/FAILED rows (which
-- become the overwhelming majority) out of the queue's hot index entirely, so
-- index size tracks backlog depth rather than total history.
CREATE INDEX idx_deliveries_claim
    ON deliveries (next_attempt_at)
    WHERE status IN ('PENDING', 'IN_PROGRESS');

-- Dashboard / API list queries.
CREATE INDEX idx_deliveries_owner_created ON deliveries (owner_id, created_at DESC);
CREATE INDEX idx_deliveries_owner_status ON deliveries (owner_id, status, created_at DESC);
CREATE INDEX idx_deliveries_event ON deliveries (event_id);
CREATE INDEX idx_deliveries_endpoint ON deliveries (endpoint_id);


-- ---------------------------------------------------------------------------
--  delivery_attempts — append-only. Never UPDATEd, never DELETEd.
-- ---------------------------------------------------------------------------
CREATE TABLE delivery_attempts
(
    id                    UUID        PRIMARY KEY,
    delivery_id           UUID        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    attempt_number        INTEGER     NOT NULL,
    outcome               TEXT        NOT NULL,
    status_code           INTEGER,
    response_time_ms      INTEGER,
    error_message         TEXT,
    -- First N bytes of the response, for debugging. Truncated on write.
    response_body_snippet TEXT,
    -- The signature header we sent, so a user can reproduce the HMAC.
    request_signature     TEXT,
    worker_id             TEXT,
    attempted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_attempts_outcome
        CHECK (outcome IN ('SUCCESS', 'RETRYABLE_ERROR', 'PERMANENT_ERROR', 'BLOCKED')),
    CONSTRAINT uq_attempts_delivery_number UNIQUE (delivery_id, attempt_number)
);

-- The delivery-detail page reads the whole timeline for one delivery in order.
CREATE INDEX idx_attempts_delivery ON delivery_attempts (delivery_id, attempt_number);
CREATE INDEX idx_attempts_attempted_at ON delivery_attempts (attempted_at DESC);
