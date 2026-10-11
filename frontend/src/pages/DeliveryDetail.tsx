import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../lib/api'
import { absoluteTime, duration, relativeTime } from '../lib/format'
import { AppShell } from '../components/AppShell'
import { AttemptRail } from '../components/AttemptRail'
import { CopyButton, IdChip } from '../components/CopyButton'
import { JsonViewer } from '../components/JsonViewer'
import { OutcomeMark, StatusCode, StatusMark } from '../components/StatusMark'
import { ErrorState, PrimaryButton, Spinner } from '../components/Primitives'
import type { Attempt } from '../lib/types'

/**
 * One delivery, in full.
 *
 * The page is ordered by what someone arriving here is trying to do: see the state, see the shape
 * of what happened (the rail), then read the detail of the attempt that matters (usually the last
 * one), then look at what was actually sent. The replay button is in the header because it is the
 * decision the page exists to support.
 *
 * @param props.onDisconnect clears the stored API key
 */
export function DeliveryDetail({ onDisconnect }: { onDisconnect: () => void }) {
  const { id = '' } = useParams()
  const queryClient = useQueryClient()

  const detail = useQuery({
    queryKey: ['delivery', id],
    queryFn: () => api.delivery(id),
    // Polls while the delivery is still moving, and stops once it is terminal — no point asking a
    // finished row whether it has changed.
    refetchInterval: (query) => {
      const status = query.state.data?.delivery.status
      return status === 'PENDING' || status === 'IN_PROGRESS' ? 2000 : false
    },
  })

  const replay = useMutation({
    mutationFn: () => api.replay(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['delivery', id] })
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['stats'] })
    },
  })

  if (detail.isError) {
    return (
      <AppShell title="Delivery" onDisconnect={onDisconnect}>
        <ErrorState error={detail.error} retry={() => void detail.refetch()} />
      </AppShell>
    )
  }

  if (!detail.data) {
    return (
      <AppShell title="Delivery" onDisconnect={onDisconnect}>
        <div className="px-5 py-10">
          <div className="rx-sweep relative h-24 overflow-hidden rounded-lg bg-surface-2" />
        </div>
      </AppShell>
    )
  }

  const { delivery, attempts, endpoint, eventPayload } = detail.data
  const replayable = delivery.status === 'FAILED' || delivery.status === 'DELIVERED'
  const lastAttempt = attempts.at(-1)

  return (
    <AppShell
      title={delivery.eventType ?? 'Delivery'}
      subtitle={`to ${delivery.endpointName ?? 'a deleted endpoint'}`}
      onDisconnect={onDisconnect}
      actions={
        <>
          <Link
            to="/deliveries"
            className="text-[12.5px] text-dim underline decoration-line underline-offset-3 transition-colors hover:text-ink"
          >
            All deliveries
          </Link>
          {replayable ? (
            <PrimaryButton busy={replay.isPending} onClick={() => replay.mutate()}>
              Send again
            </PrimaryButton>
          ) : (
            <span className="flex items-center gap-1.5 text-[12.5px] text-mute">
              <Spinner className="size-3" />
              {delivery.status === 'PENDING' ? 'Waiting to retry' : 'Sending'}
            </span>
          )}
        </>
      }
    >
      {/* State band */}
      <section className="flex flex-wrap gap-x-10 gap-y-3 border-b border-line px-5 py-3.5">
        <Fact label="Status">
          <StatusMark status={delivery.status} />
        </Fact>
        <Fact label="Attempts">
          <span className="tabular text-[13px]">
            {delivery.attemptCount} of {delivery.maxAttempts}
          </span>
        </Fact>
        <Fact label="Last reply">
          <StatusCode code={delivery.lastStatusCode} />
        </Fact>
        <Fact label={delivery.status === 'PENDING' ? 'Next attempt' : 'Finished'}>
          <span
            className="text-[13px]"
            title={absoluteTime(
              delivery.status === 'PENDING' ? delivery.nextAttemptAt : delivery.completedAt,
            )}
          >
            {relativeTime(
              delivery.status === 'PENDING' ? delivery.nextAttemptAt : delivery.completedAt,
            )}
          </span>
        </Fact>
        {delivery.replayCount > 0 && (
          <Fact label="Sent again by hand">
            <span className="tabular text-[13px] text-amber">{delivery.replayCount}×</span>
          </Fact>
        )}
      </section>

      {/* The one bold element on the page */}
      <AttemptRail attempts={attempts} />

      {delivery.lastError && (
        <section className="border-b border-line px-5 py-3">
          <div className="text-[12px] text-mute">Latest error</div>
          <p className="mono mt-1 text-[12.5px] leading-relaxed text-rose">{delivery.lastError}</p>
        </section>
      )}

      <div className="grid gap-5 px-5 py-4 lg:grid-cols-[1.4fr_1fr]">
        {/* Attempt detail */}
        <section className="min-w-0">
          <h2 className="mb-3 text-[15px]">Every attempt</h2>
          <ol className="space-y-2.5">
            {attempts.length === 0 && (
              <li className="rounded-lg border border-line px-4 py-6 text-center text-[13px] text-dim">
                No attempt has been recorded yet.
              </li>
            )}
            {[...attempts].reverse().map((attempt) => (
              <AttemptCard key={attempt.id} attempt={attempt} isLatest={attempt.id === lastAttempt?.id} />
            ))}
          </ol>
        </section>

        {/* Context */}
        <aside className="min-w-0 space-y-4">
          <div className="rounded-lg border border-line bg-surface px-4 py-3">
            <h2 className="text-[14px]">Where this went</h2>
            {endpoint ? (
              <>
                <div className="mono mt-2 flex items-start gap-1 text-[12.5px] break-all text-dim">
                  <span>{endpoint.url}</span>
                  <CopyButton value={endpoint.url} label="Copy URL" />
                </div>
                <dl className="mt-3 space-y-1.5 text-[12px]">
                  <Row label="Endpoint">{endpoint.name}</Row>
                  <Row label="Signing secret">
                    <span className="mono">{endpoint.secretPreview}</span>
                  </Row>
                  <Row label="Accepting events">{endpoint.active ? 'Yes' : 'No — disabled'}</Row>
                </dl>
              </>
            ) : (
              <p className="mt-2 text-[12.5px] text-dim">
                This endpoint has been deleted. The delivery history is kept so you can still see
                what happened.
              </p>
            )}

            <dl className="mt-3 space-y-1.5 border-t border-line-soft pt-3 text-[12px]">
              <Row label="Delivery id">
                <IdChip id={delivery.id} label="Copy delivery id" />
              </Row>
              <Row label="Event id">
                <IdChip id={delivery.eventId} label="Copy event id" />
              </Row>
            </dl>

            <p className="mt-3 border-t border-line-soft pt-3 text-[11.5px] leading-relaxed text-mute">
              Every attempt carried the same event id in <code className="mono">X-Retryx-Event-Id</code>.
              Receivers should use it to ignore anything they have already processed.
            </p>
          </div>

          <JsonViewer value={eventPayload} title="What was sent" />
        </aside>
      </div>
    </AppShell>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-[12px] text-mute">{label}</div>
      <div className="mt-1">{children}</div>
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="shrink-0 text-mute">{label}</dt>
      <dd className="min-w-0 truncate text-right text-dim">{children}</dd>
    </div>
  )
}

/**
 * One attempt.
 *
 * The response body snippet is given the most room, because in practice it is the single most
 * useful thing on this page: a receiver's own error message explains the failure far better than
 * the status code does. The signature is shown so a developer arguing about a rejected webhook can
 * reproduce the HMAC locally and find out whose calculation is wrong.
 */
function AttemptCard({ attempt, isLatest }: { attempt: Attempt; isLatest: boolean }) {
  return (
    <li
      className={`rounded-lg border bg-surface px-4 py-3 ${
        isLatest ? 'border-line' : 'border-line-soft'
      }`}
    >
      <div className="flex flex-wrap items-center gap-x-5 gap-y-1.5">
        <span className="mono text-[12.5px] text-mute">#{attempt.attemptNumber}</span>
        <OutcomeMark outcome={attempt.outcome} />
        <StatusCode code={attempt.statusCode} />
        <span className="mono tabular text-[12.5px] text-dim">
          {duration(attempt.responseTimeMs)}
        </span>
        <span className="ml-auto text-[12px] text-mute" title={absoluteTime(attempt.attemptedAt)}>
          {relativeTime(attempt.attemptedAt)}
        </span>
      </div>

      {attempt.errorMessage && (
        <p className="mono mt-2 text-[12px] leading-relaxed text-rose">{attempt.errorMessage}</p>
      )}

      {attempt.responseBodySnippet && (
        <pre className="mt-2 overflow-x-auto rounded border border-line-soft bg-bg px-2.5 py-2 text-[12px] leading-relaxed text-dim">
          <code>{attempt.responseBodySnippet}</code>
        </pre>
      )}

      <dl className="mt-2.5 flex flex-wrap gap-x-6 gap-y-1 border-t border-line-soft pt-2 text-[11.5px]">
        {attempt.workerId && (
          <div className="flex items-baseline gap-1.5">
            <dt className="text-mute">Sent by</dt>
            <dd className="mono text-dim">{attempt.workerId}</dd>
          </div>
        )}
        {attempt.requestSignature && (
          <div className="flex min-w-0 items-baseline gap-1.5">
            <dt className="shrink-0 text-mute">Signature</dt>
            <dd className="mono min-w-0 truncate text-dim" title={attempt.requestSignature}>
              {attempt.requestSignature}
            </dd>
            <CopyButton value={attempt.requestSignature} label="Copy signature header" />
          </div>
        )}
      </dl>
    </li>
  )
}
