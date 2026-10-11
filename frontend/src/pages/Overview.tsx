import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { relativeTime, shortId } from '../lib/format'
import { AppShell } from '../components/AppShell'
import { StatusStrip } from '../components/StatusStrip'
import { ThroughputChart } from '../components/ThroughputChart'
import { StatusCode, StatusMark } from '../components/StatusMark'
import { Empty, ErrorState, GhostButton, SkeletonRows, Spinner } from '../components/Primitives'

/**
 * The overview.
 *
 * Three bands, in the order a reader needs them: the figures, the shape of the last day, and the
 * things that need a decision. "Needs attention" is last because it is the only part that asks the
 * reader to act, and an action list is useless without the context above it.
 *
 * @param props.onDisconnect clears the stored API key
 */
export function Overview({ onDisconnect }: { onDisconnect: () => void }) {
  const queryClient = useQueryClient()

  const stats = useQuery({ queryKey: ['stats'], queryFn: api.stats, refetchInterval: 3000 })

  const failures = useQuery({
    queryKey: ['deliveries', { status: 'FAILED', page: 0, size: 8 }],
    queryFn: () => api.listDeliveries({ status: 'FAILED', size: 8 }),
    refetchInterval: 5000,
  })

  const replayAll = useMutation({
    mutationFn: (ids: string[]) => api.replayAll(ids),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['deliveries'] })
      void queryClient.invalidateQueries({ queryKey: ['stats'] })
    },
  })

  const failed = failures.data?.items ?? []

  return (
    <AppShell
      title="Overview"
      subtitle="How deliveries are going right now"
      onDisconnect={onDisconnect}
      actions={
        stats.isFetching ? (
          <span className="flex items-center gap-1.5 text-[12.5px] text-mute">
            <Spinner className="size-3" />
            Refreshing
          </span>
        ) : (
          <span className="text-[12.5px] text-mute">
            Updated {relativeTime(stats.data?.generatedAt)}
          </span>
        )
      }
    >
      {stats.isError ? (
        <ErrorState error={stats.error} retry={() => void stats.refetch()} />
      ) : stats.data ? (
        <>
          <StatusStrip stats={stats.data} />
          <ThroughputChart buckets={stats.data.throughput} />
        </>
      ) : (
        <div className="border-b border-line px-5 py-10">
          <div className="rx-sweep relative h-20 overflow-hidden rounded bg-surface-2" />
        </div>
      )}

      <section className="px-5 py-4">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-3">
          <h2 className="text-[15px]">Needs attention</h2>
          {failed.length > 0 && (
            <GhostButton
              onClick={() => replayAll.mutate(failed.map((delivery) => delivery.id))}
              disabled={replayAll.isPending}
            >
              {replayAll.isPending ? <Spinner className="size-3.5" /> : null}
              Send all {failed.length} again
            </GhostButton>
          )}
        </div>

        <div className="overflow-hidden rounded-lg border border-line">
          <table className="w-full text-left">
            <thead>
              <tr className="border-b border-line bg-surface text-[12px] text-mute">
                <th className="px-4 py-2 font-normal">Event</th>
                <th className="px-4 py-2 font-normal">Endpoint</th>
                <th className="px-4 py-2 font-normal">Last reply</th>
                <th className="px-4 py-2 font-normal">Attempts</th>
                <th className="px-4 py-2 font-normal">Gave up</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody>
              {failures.isLoading && <SkeletonRows rows={4} cols={6} />}

              {!failures.isLoading && failed.length === 0 && (
                <tr>
                  <td colSpan={6}>
                    <Empty
                      title="Nothing has failed"
                      body="Deliveries that exhaust their retries show up here, ready to send again once you have fixed the receiver."
                    />
                  </td>
                </tr>
              )}

              {failed.map((delivery) => (
                <tr key={delivery.id} className="border-b border-line-soft last:border-b-0 hover:bg-surface">
                  <td className="px-4 py-2.5">
                    <div className="text-[13px]">{delivery.eventType ?? 'unknown'}</div>
                    <div className="mono text-[11.5px] text-mute">{shortId(delivery.eventId)}</div>
                  </td>
                  <td className="px-4 py-2.5 text-[13px] text-dim">{delivery.endpointName ?? '—'}</td>
                  <td className="px-4 py-2.5">
                    <StatusCode code={delivery.lastStatusCode} />
                  </td>
                  <td className="tabular px-4 py-2.5 text-[13px] text-dim">
                    {delivery.attemptCount}/{delivery.maxAttempts}
                  </td>
                  <td className="px-4 py-2.5 text-[13px] text-dim">
                    {relativeTime(delivery.completedAt)}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <Link
                      to={`/deliveries/${delivery.id}`}
                      className="text-[12.5px] text-amber underline decoration-transparent underline-offset-3 transition-colors hover:decoration-current"
                    >
                      Open
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {stats.data && stats.data.inProgress > 0 && (
          <p className="mt-3 flex items-center gap-2 text-[12.5px] text-mute">
            <StatusMark status="IN_PROGRESS" compact />
            {stats.data.inProgress} {stats.data.inProgress === 1 ? 'delivery is' : 'deliveries are'}{' '}
            being sent right now.
          </p>
        )}
      </section>
    </AppShell>
  )
}
