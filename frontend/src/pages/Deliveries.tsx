import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { compactJson, count, relativeTime, shortId } from '../lib/format'
import { AppShell } from '../components/AppShell'
import { StatusCode, StatusMark, STATUS_PRESENTATION } from '../components/StatusMark'
import { Empty, ErrorState, GhostButton, PrimaryButton, SkeletonRows } from '../components/Primitives'
import type { DeliveryStatus } from '../lib/types'

const FILTERS: { value: DeliveryStatus | null; label: string }[] = [
  { value: null, label: 'All' },
  { value: 'PENDING', label: 'Waiting' },
  { value: 'IN_PROGRESS', label: 'Sending' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'FAILED', label: 'Gave up' },
]

/**
 * Events and deliveries.
 *
 * Two tabs over the same data from two angles: deliveries (one row per attempt target, which is
 * what you want when something is broken) and events (one row per thing that happened, which is
 * what you want when you are asking "did we send this at all"). Both lists poll, so a delivery
 * moving from waiting to delivered is visible without a refresh.
 *
 * @param props.onDisconnect clears the stored API key
 */
export function Deliveries({ onDisconnect }: { onDisconnect: () => void }) {
  const [view, setView] = useState<'deliveries' | 'events'>('deliveries')
  const [status, setStatus] = useState<DeliveryStatus | null>(null)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Set<string>>(new Set())

  const queryClient = useQueryClient()

  const deliveries = useQuery({
    queryKey: ['deliveries', { status, page }],
    queryFn: () => api.listDeliveries({ status, page, size: 25 }),
    refetchInterval: 3000,
    enabled: view === 'deliveries',
  })

  const events = useQuery({
    queryKey: ['events', { page }],
    queryFn: () => api.listEvents({ page, size: 25 }),
    refetchInterval: 5000,
    enabled: view === 'events',
  })

  const replaySelected = useMutation({
    mutationFn: (ids: string[]) => api.replayAll(ids),
    onSuccess: () => {
      setSelected(new Set())
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['stats'] })
    },
  })

  // Read the active view's state through explicit branches rather than
  // `view === 'deliveries' ? deliveries : events`. That ternary produces a union of two
  // differently-parameterised UseQueryResult types, and calling `.refetch()` on such a union is a
  // TypeScript error — the signatures exist on both members but are not mutually assignable.
  const showingDeliveries = view === 'deliveries'
  const total = (showingDeliveries ? deliveries.data?.total : events.data?.total) ?? 0
  const totalPages = (showingDeliveries ? deliveries.data?.totalPages : events.data?.totalPages) ?? 0
  const activeIsError = showingDeliveries ? deliveries.isError : events.isError
  const activeError = showingDeliveries ? deliveries.error : events.error

  function refetchActive() {
    if (showingDeliveries) {
      void deliveries.refetch()
    } else {
      void events.refetch()
    }
  }

  function toggleSelection(id: string) {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <AppShell
      title={view === 'deliveries' ? 'Deliveries' : 'Events'}
      subtitle={
        view === 'deliveries'
          ? 'One row per event and endpoint pair'
          : 'Everything your producers have sent to Retryx'
      }
      onDisconnect={onDisconnect}
      actions={
        selected.size > 0 && (
          <PrimaryButton
            busy={replaySelected.isPending}
            onClick={() => replaySelected.mutate([...selected])}
          >
            Send {selected.size} again
          </PrimaryButton>
        )
      }
    >
      {/* Controls: one band, hairline-separated from the table. The view switch and the status
          filter sit together because they are the same kind of decision — what am I looking at. */}
      <div className="flex flex-wrap items-center justify-between gap-x-5 gap-y-2 border-b border-line px-4 py-2.5 sm:px-5">
        <div className="flex items-center gap-3">
          <Segmented
            options={[
              { value: 'deliveries', label: 'Deliveries' },
              { value: 'events', label: 'Events' },
            ]}
            value={view}
            onChange={(next) => {
              setView(next as 'deliveries' | 'events')
              setPage(0)
              setSelected(new Set())
            }}
          />
        </div>

        <span className="tabular text-[12.5px] text-mute">{count(total)} total</span>

        {view === 'deliveries' && (
          <div className="flex w-full items-center gap-1 overflow-x-auto pb-1 pt-0.5 md:w-auto md:pb-0 md:pt-0">
            {FILTERS.map((filter) => {
              const isActive = filter.value === status
              const tone = filter.value ? STATUS_PRESENTATION[filter.value] : null

              return (
                <button
                  key={filter.label}
                  type="button"
                  onClick={() => {
                    setStatus(filter.value)
                    setPage(0)
                  }}
                  className={`flex shrink-0 items-center gap-1.5 rounded px-2.5 py-1 text-[12px] sm:text-[12.5px] transition-colors ${
                    isActive ? 'bg-surface-2 text-ink' : 'text-dim hover:bg-surface-2 hover:text-ink'
                  }`}
                >
                  {tone && <span className={`size-1.5 rounded-full ${tone.dot}`} aria-hidden />}
                  {filter.label}
                </button>
              )
            })}
          </div>
        )}
      </div>

      <section className="min-w-0 flex-1 px-4 py-4 sm:px-5">

        {activeIsError ? (
          <ErrorState error={activeError} retry={refetchActive} />
        ) : (
          <div className="overflow-x-auto rounded-lg border border-line">
            {view === 'deliveries' ? (
              <table className="w-full min-w-[860px] text-left">
                <thead>
                  <tr className="border-b border-line bg-surface text-[12px] text-mute">
                    <th className="w-8 px-3 py-2" />
                    <th className="px-4 py-2 font-normal">Status</th>
                    <th className="px-4 py-2 font-normal">Event</th>
                    <th className="px-4 py-2 font-normal">Endpoint</th>
                    <th className="px-4 py-2 font-normal">Reply</th>
                    <th className="px-4 py-2 font-normal">Attempts</th>
                    <th className="px-4 py-2 font-normal">Next / finished</th>
                    <th className="px-4 py-2 font-normal">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {deliveries.isLoading && <SkeletonRows rows={8} cols={8} />}

                  {!deliveries.isLoading && (deliveries.data?.items.length ?? 0) === 0 && (
                    <tr>
                      <td colSpan={8}>
                        <Empty
                          title={status ? 'Nothing in this state' : 'No deliveries yet'}
                          body={
                            status
                              ? 'Try another filter, or clear it to see everything.'
                              : 'Send an event to the API and a delivery will appear here for each active endpoint.'
                          }
                        />
                      </td>
                    </tr>
                  )}

                  {deliveries.data?.items.map((delivery) => {
                    const tone = STATUS_PRESENTATION[delivery.status]
                    const replayable = delivery.status === 'FAILED' || delivery.status === 'DELIVERED'

                    return (
                      <tr
                        key={delivery.id}
                        className="group border-b border-line-soft last:border-b-0 hover:bg-surface"
                      >
                        {/* The status bar is the row's left edge, not a cell with a background —
                            it scans as a continuous colour-coded margin down the table. */}
                        <td className="relative px-3 py-2.5">
                          <span className={`absolute top-0 bottom-0 left-0 w-[3px] ${tone.bar}`} aria-hidden />
                          {replayable && (
                            <input
                              type="checkbox"
                              checked={selected.has(delivery.id)}
                              onChange={() => toggleSelection(delivery.id)}
                              aria-label={`Select delivery ${shortId(delivery.id)}`}
                              className="accent-amber"
                            />
                          )}
                        </td>

                        <td className="px-4 py-2.5">
                          <StatusMark status={delivery.status} />
                        </td>

                        <td className="px-4 py-2.5">
                          <Link
                            to={`/deliveries/${delivery.id}`}
                            className="text-[13px] text-ink underline decoration-transparent underline-offset-3 transition-colors group-hover:decoration-line"
                          >
                            {delivery.eventType ?? 'unknown'}
                          </Link>
                          <div className="mono text-[11.5px] text-mute">{shortId(delivery.eventId)}</div>
                        </td>

                        <td className="max-w-[180px] truncate px-4 py-2.5 text-[13px] text-dim">
                          {delivery.endpointName ?? '—'}
                        </td>

                        <td className="px-4 py-2.5">
                          <StatusCode code={delivery.lastStatusCode} />
                        </td>

                        <td className="tabular px-4 py-2.5 text-[13px] text-dim">
                          {delivery.attemptCount}/{delivery.maxAttempts}
                          {delivery.replayCount > 0 && (
                            <span className="ml-1.5 text-[11.5px] text-amber">
                              ↺{delivery.replayCount}
                            </span>
                          )}
                        </td>

                        <td className="px-4 py-2.5 text-[13px] text-dim">
                          {delivery.status === 'PENDING'
                            ? relativeTime(delivery.nextAttemptAt)
                            : relativeTime(delivery.completedAt)}
                        </td>

                        <td className="px-4 py-2.5 text-[13px] text-mute">
                          {relativeTime(delivery.createdAt)}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            ) : (
              <table className="w-full min-w-[720px] text-left">
                <thead>
                  <tr className="border-b border-line bg-surface text-[12px] text-mute">
                    <th className="px-4 py-2 font-normal">Type</th>
                    <th className="px-4 py-2 font-normal">Payload</th>
                    <th className="px-4 py-2 font-normal">Idempotency key</th>
                    <th className="px-4 py-2 font-normal">Received</th>
                  </tr>
                </thead>
                <tbody>
                  {events.isLoading && <SkeletonRows rows={8} cols={4} />}

                  {!events.isLoading && (events.data?.items.length ?? 0) === 0 && (
                    <tr>
                      <td colSpan={4}>
                        <Empty
                          title="No events yet"
                          body="POST to /api/events with your API key and the event will show up here."
                        />
                      </td>
                    </tr>
                  )}

                  {events.data?.items.map((event) => (
                    <tr key={event.id} className="border-b border-line-soft last:border-b-0 hover:bg-surface">
                      <td className="px-4 py-2.5">
                        <div className="text-[13px]">{event.eventType}</div>
                        <div className="mono text-[11.5px] text-mute">{shortId(event.id)}</div>
                      </td>
                      <td className="max-w-[320px] px-4 py-2.5">
                        <code className="mono truncate text-[12px] text-dim">
                          {compactJson(event.payload)}
                        </code>
                      </td>
                      <td className="px-4 py-2.5">
                        {event.idempotencyKey ? (
                          <code className="mono text-[12px] text-dim">{event.idempotencyKey}</code>
                        ) : (
                          <span className="text-[12.5px] text-mute">none</span>
                        )}
                      </td>
                      <td className="px-4 py-2.5 text-[13px] text-mute">
                        {relativeTime(event.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}

        {totalPages > 1 && (
          <div className="mt-3 flex items-center justify-between">
            <span className="tabular text-[12.5px] text-mute">
              Page {page + 1} of {totalPages}
            </span>
            <div className="flex gap-2">
              <GhostButton onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0}>
                Previous
              </GhostButton>
              <GhostButton
                onClick={() => setPage((p) => p + 1)}
                disabled={page >= totalPages - 1}
              >
                Next
              </GhostButton>
            </div>
          </div>
        )}
      </section>
    </AppShell>
  )
}

/** A two- or three-way switch. One control, not a row of buttons that look like actions. */
function Segmented({
  options,
  value,
  onChange,
}: {
  options: { value: string; label: string }[]
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="flex rounded border border-line p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
          aria-pressed={option.value === value}
          className={`rounded px-2.5 py-1 text-[12.5px] transition-colors ${
            option.value === value ? 'bg-surface-2 text-ink' : 'text-dim hover:text-ink'
          }`}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
