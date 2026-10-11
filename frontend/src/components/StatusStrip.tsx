import { count, duration } from '../lib/format'
import type { Stats } from '../lib/types'

/**
 * The figures that matter, as one band.
 *
 * A band divided by hairlines rather than a row of cards. Four identical rounded cards with the
 * same shadow is the default dashboard treatment, and it is the wrong one here: cards imply the
 * figures are separate things you might rearrange, when they are actually one readout of a single
 * system — delivered, retrying and failed are three slices of the same population, and the success
 * rate is derived from two of them. Hairline-separated segments say "one instrument, several
 * needles", which is what this is.
 *
 * The success rate is given the most visual weight because it is the only figure here that is a
 * judgement rather than a count.
 */
export function StatusStrip({ stats }: { stats: Stats }) {
  const health =
    stats.successRate >= 99 ? 'text-emerald' : stats.successRate >= 90 ? 'text-amber' : 'text-rose'

  return (
    <section className="grid grid-cols-2 border-b border-line sm:grid-cols-3 lg:grid-cols-6">
      <Figure
        label="Delivered"
        value={count(stats.delivered)}
        hint={`of ${count(stats.delivered + stats.failed)} finished`}
      />
      <Figure
        label="Success rate"
        value={`${stats.successRate.toFixed(1)}%`}
        tone={health}
        emphasis
        hint="of deliveries that finished"
      />
      <Figure label="Waiting to retry" value={count(stats.pending)} tone={stats.pending > 0 ? 'text-amber' : undefined} />
      <Figure label="Gave up" value={count(stats.failed)} tone={stats.failed > 0 ? 'text-rose' : undefined} hint="replayable" />
      <Figure
        label="Receiver p95"
        value={duration(stats.p95ResponseMs)}
        hint={stats.avgResponseMs !== null ? `${duration(stats.avgResponseMs)} average` : 'no attempts yet'}
      />
      <Figure label="Events" value={count(stats.totalEvents)} hint={`${count(stats.activeEndpoints)} endpoints`} />
    </section>
  )
}

function Figure({
  label,
  value,
  hint,
  tone,
  emphasis = false,
}: {
  label: string
  value: string
  hint?: string
  tone?: string
  emphasis?: boolean
}) {
  return (
    <div className="border-b border-r border-line-soft px-3.5 py-3 sm:px-5 sm:py-3.5 lg:border-b-0 last:border-r-0">
      <div className="text-[11.5px] sm:text-[12px] text-mute">{label}</div>
      <div
        className={`tabular mt-1 leading-none tracking-tight ${
          emphasis ? 'text-[22px] sm:text-[28px] font-semibold' : 'text-[19px] sm:text-[22px] font-medium'
        } ${tone ?? 'text-ink'}`}
      >
        {value}
      </div>
      {hint && <div className="mt-1 truncate text-[11px] sm:text-[11.5px] text-mute">{hint}</div>}
    </div>
  )
}

