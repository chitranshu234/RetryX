import { count, hourLabel } from '../lib/format'
import type { ThroughputBucket } from '../lib/types'

/**
 * Deliveries per hour for the last 24 hours, as stacked columns.
 *
 * Hand-drawn SVG rather than a chart library. Three reasons, in order of weight: the shape is
 * fixed and simple (24 stacked columns, no zoom, no pan, no legend interaction), so a library's
 * flexibility buys nothing; the colours have to come from the theme tokens so the chart repaints
 * with the rest of the interface, which means overriding a library's palette anyway; and it keeps
 * ~50KB of JavaScript out of a dashboard whose job is to load fast while someone is debugging an
 * outage.
 *
 * ## Why stacked, and why retries are their own band
 *
 * The three series answer one question each: did it arrive, did we give up, and how hard did we
 * have to work. Stacking them makes total activity the column height, which is what you scan for
 * first. Separating retries from failures is the important part — a healthy system pointed at a
 * flaky receiver has a tall amber band and a tall green one at the same time, and folding those
 * together into "errors" would hide exactly the behaviour the retry engine exists to produce.
 *
 * Failures sit at the bottom of the stack, against the axis, because a stacked band is only easy to
 * compare across columns when it has a straight baseline — and "is this getting worse" is a
 * question asked of failures, not of successes.
 */
export function ThroughputChart({ buckets }: { buckets: ThroughputBucket[] }) {
  const totals = buckets.map((bucket) => bucket.delivered + bucket.failed + bucket.retried)
  const peak = Math.max(1, ...totals)
  const grandTotal = totals.reduce((sum, value) => sum + value, 0)

  const height = 108
  const gap = 3

  if (grandTotal === 0) {
    return (
      <section className="border-b border-line px-5 py-4">
        <Header total={0} />
        <div className="flex h-[108px] items-center justify-center text-[13px] text-mute">
          Nothing delivered in the last 24 hours.
        </div>
        <Axis buckets={buckets} />
      </section>
    )
  }

  return (
    <section className="border-b border-line px-5 py-4">
      <Header total={grandTotal} />

      <div className="flex items-end gap-[3px]" style={{ height }} role="img"
           aria-label={`Delivery attempts per hour over the last 24 hours. ${count(grandTotal)} attempts in total.`}>
        {buckets.map((bucket, index) => {
          const total = totals[index]
          const scale = (value: number) => (value / peak) * height

          return (
            <div
              key={bucket.hour}
              className="group relative flex flex-1 flex-col justify-end"
              style={{ minHeight: 1 }}
            >
              {/* Hover readout. Positioned above the column and allowed to overflow the chart. */}
              <div className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1.5 hidden -translate-x-1/2 rounded border border-line bg-surface-2 px-2 py-1.5 text-[11.5px] whitespace-nowrap shadow-md group-hover:block">
                <div className="mono text-dim">{hourLabel(bucket.hour)}</div>
                <div className="mt-0.5 flex gap-2.5">
                  <span className="text-emerald">{count(bucket.delivered)} ok</span>
                  <span className="text-amber">{count(bucket.retried)} retried</span>
                  <span className="text-rose">{count(bucket.failed)} failed</span>
                </div>
              </div>

              {/* Zero-activity hours get a 1px tick rather than nothing, so a quiet hour is
                  visibly a quiet hour and not a gap in the data. */}
              {total === 0 ? (
                <div className="h-px w-full bg-line" />
              ) : (
                <div className="flex w-full flex-col justify-end transition-opacity group-hover:opacity-80">
                  <div style={{ height: scale(bucket.delivered) }} className="w-full bg-emerald" />
                  <div style={{ height: scale(bucket.retried) }} className="w-full bg-amber" />
                  <div style={{ height: scale(bucket.failed) }} className="w-full bg-rose" />
                </div>
              )}
            </div>
          )
        })}
      </div>

      <Axis buckets={buckets} gap={gap} />
    </section>
  )
}

function Header({ total }: { total: number }) {
  return (
    <div className="mb-3 flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
      <h2 className="text-[15px]">Delivery attempts</h2>
      <div className="flex items-center gap-4 text-[12.5px]">
        <Key colour="bg-emerald" label="accepted" />
        <Key colour="bg-amber" label="retried" />
        <Key colour="bg-rose" label="failed" />
        <span className="tabular text-mute">{count(total)} in 24h</span>
      </div>
    </div>
  )
}

function Key({ colour, label }: { colour: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-dim">
      <span className={`size-2 rounded-sm ${colour}`} aria-hidden />
      {label}
    </span>
  )
}

/** Hour labels every six columns — enough to orient, few enough not to crowd. */
function Axis({ buckets, gap = 3 }: { buckets: ThroughputBucket[]; gap?: number }) {
  return (
    <div className="mt-1.5 flex" style={{ gap: `${gap}px` }}>
      {buckets.map((bucket, index) => (
        <div key={bucket.hour} className="flex-1 text-center">
          {index % 6 === 0 && (
            <span className="mono text-[10.5px] text-mute">{hourLabel(bucket.hour)}</span>
          )}
        </div>
      ))}
    </div>
  )
}
