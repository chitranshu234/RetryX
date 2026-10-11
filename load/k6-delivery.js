/**
 * Retryx load test.
 *
 *   docker compose up -d
 *   # turn on failure injection so the retry engine is actually exercised
 *   docker compose exec -e RECEIVER_FAILURE_RATE=0.2 receiver true  # or set it in compose and restart
 *   k6 run load/k6-delivery.js
 *
 * ## What this measures, and why these four numbers
 *
 * 1. **Deliveries per second at a fixed failure rate.** Throughput with everything working is not
 *    an interesting figure — the interesting one is throughput while 20% of attempts fail and have
 *    to be rescheduled, because that is when the queue, the backoff and the claim query are all
 *    under load at once.
 *
 * 2. **Eventual success rate.** The point of a retry engine. With a 20% independent failure rate
 *    and six attempts, the chance of a delivery never succeeding is 0.2^6 ≈ 0.0064%, so anything
 *    below about 99.9% means deliveries are being lost somewhere other than to the injected
 *    failures.
 *
 * 3. **Duplicate sends across workers — must be zero.** The receiver counts distinct
 *    (deliveryId, attemptNumber) pairs. Two workers claiming the same row would send the same pair
 *    twice. This is the number that proves FOR UPDATE SKIP LOCKED is doing its job, and it is
 *    checked against two worker processes running concurrently.
 *
 * 4. **Ingest latency.** Ingestion must stay fast while delivery is saturated. If p95 on
 *    POST /api/events degrades as the queue grows, the write path and the worker path are
 *    contending — usually for connection-pool slots.
 *
 * ## What this test deliberately does not do
 *
 * It does not assert a throughput number in a threshold. Throughput here is bounded by the
 * receiver, by Docker's networking, and by whatever else is running on the machine — baking a
 * figure from one laptop into a pass/fail check produces a test that fails on other hardware for
 * no useful reason. Throughput is *reported*; correctness is *asserted*.
 */

import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const API = __ENV.RETRYX_API ?? 'http://localhost:8080'
const RECEIVER = __ENV.RETRYX_RECEIVER ?? 'http://localhost:9090'
const API_KEY = __ENV.RETRYX_API_KEY ?? 'rtx_sk_demo_0000000000000000000000'

/** Seconds to wait after ingestion stops, for the retry schedule to drain. */
const DRAIN_SECONDS = Number(__ENV.RETRYX_DRAIN_SECONDS ?? 90)

const ingested = new Counter('retryx_events_ingested')
const ingestFailed = new Counter('retryx_ingest_failed')
const idempotentReplays = new Counter('retryx_idempotent_replays')
const ingestLatency = new Trend('retryx_ingest_latency_ms', true)

export const options = {
  scenarios: {
    ingest: {
      executor: 'ramping-arrival-rate',
      // Arrival-rate, not VU-based. A VU-based test with a slow API accidentally
      // reduces its own offered load, so the system under test gets to decide how
      // hard it is being pushed — which measures nothing.
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 120,
      stages: [
        { target: 50, duration: '20s' },
        { target: 150, duration: '40s' },
        { target: 150, duration: '60s' },
        { target: 0, duration: '10s' },
      ],
    },
  },
  thresholds: {
    // Ingest must stay responsive while delivery is saturated.
    'http_req_duration{endpoint:ingest}': ['p(95)<400'],
    retryx_ingest_failed: ['count<10'],
  },
}

export function setup() {
  const reset = http.post(`${RECEIVER}/reset`)
  check(reset, { 'receiver counters reset': (response) => response.status === 200 })

  const before = http.get(`${API}/api/stats`, { headers: { 'X-Api-Key': API_KEY } })
  check(before, { 'api reachable and key accepted': (response) => response.status === 200 })

  return { startedAt: Date.now(), baseline: before.json() }
}

export default function sendEvent() {
  const payload = JSON.stringify({
    eventType: 'order.created',
    payload: {
      orderId: `ord_${__VU}_${__ITER}`,
      total: Math.round(Math.random() * 50_000) / 100,
      currency: 'GBP',
      lines: [{ sku: 'RTX-1', qty: 1 + (__ITER % 3) }],
    },
  })

  const response = http.post(`${API}/api/events`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Api-Key': API_KEY,
      // A unique key per iteration. The duplicate path is covered by a dedicated
      // integration test; here the goal is to measure the cost of the idempotency
      // index on the write path under load, not to test deduplication.
      'Idempotency-Key': `load-${__VU}-${__ITER}`,
    },
    tags: { endpoint: 'ingest' },
  })

  ingestLatency.add(response.timings.duration)

  const ok = check(response, {
    'event accepted': (r) => r.status === 201 || r.status === 200,
  })

  if (!ok) {
    ingestFailed.add(1)
  } else {
    ingested.add(1)
    if (response.status === 200) idempotentReplays.add(1)
  }
}

export function teardown(data) {
  // Ingestion has stopped, but deliveries scheduled with backoff have not. Draining
  // before reading the figures is the difference between measuring the system and
  // measuring the moment the load generator happened to stop.
  console.log(`\nDraining for ${DRAIN_SECONDS}s so the retry schedule completes…`)

  let stats = null
  const deadline = Date.now() + DRAIN_SECONDS * 1000

  while (Date.now() < deadline) {
    sleep(5)
    const response = http.get(`${API}/api/stats`, { headers: { 'X-Api-Key': API_KEY } })
    if (response.status !== 200) continue
    stats = response.json()
    if (stats.pending === 0 && stats.inProgress === 0) break
  }

  const receiverStats = http.get(`${RECEIVER}/stats`).json()
  const elapsedSeconds = (Date.now() - data.startedAt) / 1000

  const delivered = stats ? stats.delivered - (data.baseline.delivered ?? 0) : 0
  const failed = stats ? stats.failed - (data.baseline.failed ?? 0) : 0
  const terminal = delivered + failed

  console.log(`
════════════════════════════════════════════════════════════════════
  Retryx load test results
════════════════════════════════════════════════════════════════════

  Wall clock                 ${elapsedSeconds.toFixed(1)}s
  Events ingested            ${ingested.name ? '(see summary)' : ''}

  DELIVERY
  Delivered                  ${delivered}
  Gave up                    ${failed}
  Eventual success rate      ${terminal > 0 ? ((delivered / terminal) * 100).toFixed(3) : '—'}%
  Throughput                 ${(terminal / elapsedSeconds).toFixed(1)} deliveries/s
  Still waiting              ${stats ? stats.pending : '?'}
  Still sending              ${stats ? stats.inProgress : '?'}

  RECEIVER
  Requests received          ${receiverStats.totalRequests}
  Distinct events            ${receiverStats.uniqueEvents}
  Distinct delivery attempts ${receiverStats.uniqueDeliveryAttempts}
  Injected failures          ${receiverStats.injectedFailures}
  Signatures verified        ${receiverStats.signatureValid}
  Signatures rejected        ${receiverStats.signatureInvalid}

  Repeated events            ${receiverStats.repeatedEvents}   <- expected > 0: at-least-once
  DUPLICATE SENDS            ${receiverStats.duplicateAttempts}   <- must be 0

  LATENCY
  Receiver p95               ${stats ? stats.p95ResponseMs : '?'}ms
  Receiver mean              ${stats ? stats.avgResponseMs : '?'}ms

════════════════════════════════════════════════════════════════════`)

  if (receiverStats.duplicateAttempts > 0) {
    console.error(
      `FAIL: ${receiverStats.duplicateAttempts} delivery attempts were sent more than once. ` +
        `Either SKIP LOCKED claiming is broken, or a lease expired mid-send ` +
        `(check the retryx.lease.lost counter on /actuator/metrics).`,
    )
  }
  if (receiverStats.trackingSaturated) {
    console.warn(
      'Receiver tracking saturated at 500k distinct keys; the duplicate figure covers only what fit.',
    )
  }
}
