/**
 * Types mirroring the backend's response DTOs.
 *
 * Kept hand-written rather than generated. The API surface is small and stable, and a hand-written
 * mirror makes a breaking backend change show up as a TypeScript error in the component that reads
 * the field, which is where you want to be told. The cost is that these must be updated alongside
 * `dev.retryx.web.dto` — the field names here match that package exactly.
 */

/** Where a delivery is right now. Mirrors `dev.retryx.domain.DeliveryStatus`. */
export type DeliveryStatus = 'PENDING' | 'IN_PROGRESS' | 'DELIVERED' | 'FAILED'

/** What happened on one attempt. Mirrors `dev.retryx.domain.AttemptOutcome`. */
export type AttemptOutcome = 'SUCCESS' | 'RETRYABLE_ERROR' | 'PERMANENT_ERROR' | 'BLOCKED'

export interface Endpoint {
  id: string
  name: string
  url: string
  description: string | null
  active: boolean
  /** Display hint only. The real secret is returned once, at creation, and never again. */
  secretPreview: string
  maxAttempts: number | null
  createdAt: string
  updatedAt: string
}

export interface CreatedEndpoint {
  endpoint: Endpoint
  /** Shown once. There is no endpoint that can return it a second time. */
  signingSecret: string
}

export interface Delivery {
  id: string
  eventId: string
  endpointId: string
  endpointName: string | null
  eventType: string | null
  status: DeliveryStatus
  attemptCount: number
  maxAttempts: number
  nextAttemptAt: string
  lastStatusCode: number | null
  lastError: string | null
  replayCount: number
  completedAt: string | null
  createdAt: string
}

export interface Attempt {
  id: string
  attemptNumber: number
  outcome: AttemptOutcome
  statusCode: number | null
  responseTimeMs: number | null
  errorMessage: string | null
  responseBodySnippet: string | null
  requestSignature: string | null
  workerId: string | null
  attemptedAt: string
}

export interface DeliveryDetail {
  delivery: Delivery
  attempts: Attempt[]
  endpoint: Endpoint | null
  eventType: string | null
  eventPayload: unknown
}

export interface RetryxEvent {
  id: string
  eventType: string
  payload: unknown
  idempotencyKey: string | null
  createdAt: string
  deliveries: Delivery[] | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export interface ThroughputBucket {
  hour: string
  delivered: number
  failed: number
  retried: number
}

export interface Stats {
  pending: number
  inProgress: number
  delivered: number
  failed: number
  totalEvents: number
  activeEndpoints: number
  successRate: number
  dueNow: number
  oldestDueAgeSec: number
  avgResponseMs: number | null
  p95ResponseMs: number | null
  throughput: ThroughputBucket[]
  generatedAt: string
}

export interface ApiError {
  error: string
  message: string
  status: number
  fields?: Record<string, string>
  timestamp: string
}
