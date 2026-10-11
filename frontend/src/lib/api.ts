import type {
  CreatedEndpoint,
  Delivery,
  DeliveryDetail,
  DeliveryStatus,
  Endpoint,
  Page,
  RetryxEvent,
  Stats,
} from './types'

/**
 * The API client.
 *
 * One place that knows how to talk to Retryx, so no component contains a `fetch`. That matters for
 * two reasons beyond tidiness: the API key is attached in exactly one place, and error handling is
 * uniform — every failure arrives at the UI as a `RetryxApiError` carrying the server's own
 * message, rather than as whatever shape that particular call site happened to produce.
 */

const API_BASE = '/api'
const STORAGE_KEY = 'retryx.apiKey'

/** A failed request, carrying the server's message and status. */
export class RetryxApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fields?: Record<string, string>

  constructor(status: number, code: string, message: string, fields?: Record<string, string>) {
    super(message)
    this.name = 'RetryxApiError'
    this.status = status
    this.code = code
    this.fields = fields
  }

  /** @returns true when the key is missing, wrong or revoked, and the user needs to reconnect */
  get isUnauthorized(): boolean {
    return this.status === 401
  }
}

/**
 * The API key, in `localStorage`.
 *
 * `localStorage` is a deliberate, bounded choice. It is readable by any script on the origin, so an
 * XSS bug here leaks the key — which is the standard argument for an httpOnly cookie instead. That
 * argument does not apply: Retryx has no login, no session, and no server-rendered page that could
 * set such a cookie, and the key authenticates a tenant of a self-hosted operations tool rather
 * than a person's account. The real mitigation is that keys are revocable server-side, and the
 * honest statement of the trade-off belongs in the code rather than in a footnote.
 */
export const apiKeyStore = {
  get(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEY)
    } catch {
      return null
    }
  },
  set(key: string): void {
    try {
      localStorage.setItem(STORAGE_KEY, key.trim())
    } catch {
      /* storage disabled; the key stays in memory for this page only */
    }
  },
  clear(): void {
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      /* nothing to clear */
    }
  },
}

/**
 * Performs one request.
 *
 * @param path   path under /api
 * @param init   fetch options
 * @returns the parsed JSON body, or undefined for 204
 * @throws RetryxApiError on any non-2xx response
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const key = apiKeyStore.get()

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(key ? { 'X-Api-Key': key } : {}),
      ...init.headers,
    },
  })

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  const body = text ? safeParse(text) : null

  if (!response.ok) {
    const parsed = body as { error?: string; message?: string; fields?: Record<string, string> } | null
    throw new RetryxApiError(
      response.status,
      parsed?.error ?? 'request_failed',
      parsed?.message ?? `${response.status} ${response.statusText}`,
      parsed?.fields,
    )
  }

  return body as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

/** Every call the dashboard makes. */
export const api = {
  /**
   * Verifies a key by making the cheapest authenticated call available.
   *
   * @param key candidate API key
   * @returns true if the key authenticates
   */
  async verifyKey(key: string): Promise<boolean> {
    const response = await fetch(`${API_BASE}/webhooks`, { headers: { 'X-Api-Key': key.trim() } })
    return response.ok
  },

  stats(): Promise<Stats> {
    return request<Stats>('/stats')
  },

  listEndpoints(): Promise<Endpoint[]> {
    return request<Endpoint[]>('/webhooks')
  },

  createEndpoint(input: {
    name: string
    url: string
    description?: string
    maxAttempts?: number
  }): Promise<CreatedEndpoint> {
    return request<CreatedEndpoint>('/webhooks', {
      method: 'POST',
      body: JSON.stringify(input),
    })
  },

  setEndpointActive(id: string, active: boolean): Promise<Endpoint> {
    return request<Endpoint>(`/webhooks/${id}/active?active=${active}`, { method: 'PUT' })
  },

  deleteEndpoint(id: string): Promise<void> {
    return request<void>(`/webhooks/${id}`, { method: 'DELETE' })
  },

  listEvents(params: { page?: number; size?: number; eventType?: string } = {}): Promise<Page<RetryxEvent>> {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 20))
    if (params.eventType) query.set('eventType', params.eventType)
    return request<Page<RetryxEvent>>(`/events?${query}`)
  },

  sendEvent(input: {
    eventType: string
    payload: unknown
    idempotencyKey?: string
  }): Promise<RetryxEvent> {
    return request<RetryxEvent>('/events', {
      method: 'POST',
      headers: input.idempotencyKey ? { 'Idempotency-Key': input.idempotencyKey } : {},
      body: JSON.stringify({ eventType: input.eventType, payload: input.payload }),
    })
  },

  listDeliveries(
    params: { page?: number; size?: number; status?: DeliveryStatus | null } = {},
  ): Promise<Page<Delivery>> {
    const query = new URLSearchParams()
    query.set('page', String(params.page ?? 0))
    query.set('size', String(params.size ?? 25))
    if (params.status) query.set('status', params.status)
    return request<Page<Delivery>>(`/deliveries?${query}`)
  },

  delivery(id: string): Promise<DeliveryDetail> {
    return request<DeliveryDetail>(`/deliveries/${id}`)
  },

  replay(id: string): Promise<Delivery> {
    return request<Delivery>(`/deliveries/${id}/replay`, { method: 'POST' })
  },

  replayAll(ids: string[]): Promise<{ requested: number; replayed: number; ids: string[] }> {
    return request('/deliveries/replay', {
      method: 'POST',
      body: JSON.stringify({ ids }),
    })
  },
}
