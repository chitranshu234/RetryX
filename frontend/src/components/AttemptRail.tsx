import { duration, relativeTime } from '../lib/format'
import type { Attempt } from '../lib/types'
import { OUTCOME_PRESENTATION } from './StatusMark'

/**
 * The attempt timeline, drawn to scale.
 *
 * This is the one element in the dashboard that exists nowhere else, and the reason is that the
 * characteristic artifact of this domain is not a count — it is a *schedule*. A delivery is a
 * sequence of attempts separated by exponentially growing gaps, and that shape is the whole
 * behaviour of the retry engine made visible. Rendering the attempts as a plain list throws it
 * away: six rows tell you that six things happened, but not that the first two were two seconds
 * apart and the last two were half a minute apart, which is the thing someone debugging a delivery
 * actually wants to know.
 *
 * So the horizontal position of each mark is proportional to when it happened. You can see the
 * backoff doubling, see where the receiver started failing, and see how long the whole delivery
 * took, without reading a single number.
 *
 * ## The scale problem, and how it is handled honestly
 *
 * Exponential data on a linear axis crushes the early points together: with a 2s first gap and a
 * 32s last one, the first three attempts land on top of each other. The usual fix is a log axis,
 * applied silently — which quietly turns a chart of time into a chart of something else, and a
 * reader who misses the axis label misreads it completely.
 *
 * Here the axis stays linear until the ratio between the largest and smallest gap exceeds
 * {@link COMPRESS_THRESHOLD}, at which point it switches to a square-root scale *and says so* in
 * the caption. Compressed rather than logarithmic because a square root still preserves the visual
 * sense of "much bigger", where a log makes 2s and 32s look nearly adjacent — and the point of the
 * picture is that the gaps grow.
 */

/** Gap ratio above which the axis compresses. */
const COMPRESS_THRESHOLD = 12

export function AttemptRail({ attempts }: { attempts: Attempt[] }) {
  if (attempts.length === 0) {
    return (
      <div className="border-y border-line bg-surface px-5 py-8 text-center text-[13px] text-dim">
        No attempt has been made yet. A worker will pick this up within a second.
      </div>
    )
  }

  const times = attempts.map((attempt) => new Date(attempt.attemptedAt).getTime())
  const start = times[0]
  const end = times[times.length - 1]
  const span = end - start

  // Gaps between consecutive attempts, used both for the axis decision and the labels.
  const gaps = times.slice(1).map((time, index) => time - times[index])
  const smallestGap = gaps.length > 0 ? Math.min(...gaps) : 0
  const largestGap = gaps.length > 0 ? Math.max(...gaps) : 0
  const compressed = smallestGap > 0 && largestGap / smallestGap > COMPRESS_THRESHOLD

  /**
   * Maps a timestamp to a percentage across the rail.
   *
   * A single attempt, or several within the same millisecond, gives a zero span — which would
   * divide by zero and produce NaN positions. That case is laid out evenly instead.
   */
  function position(time: number, index: number): number {
    if (span <= 0) {
      return attempts.length === 1 ? 50 : (index / (attempts.length - 1)) * 100
    }
    const linear = (time - start) / span
    return (compressed ? Math.sqrt(linear) : linear) * 100
  }

  return (
    <section className="border-y border-line bg-surface">
      <header className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 px-5 pt-4">
        <h2 className="text-[15px]">Attempt timeline</h2>
        <p className="text-[12.5px] text-mute">
          {attempts.length} {attempts.length === 1 ? 'attempt' : 'attempts'} over{' '}
          <span className="tabular text-dim">{duration(span)}</span>
          {compressed && <span> · spacing compressed so the early attempts stay readable</span>}
        </p>
      </header>

      {/* Generous bottom padding: the labels below each mark are absolutely positioned and would
          otherwise collide with whatever follows this section. */}
      <div className="px-5 pt-10 pb-14">
        <div className="relative h-px bg-line">
          {attempts.map((attempt, index) => {
            const left = position(times[index], index)
            const style = OUTCOME_PRESENTATION[attempt.outcome]

            // Keep the first and last labels inside the rail rather than hanging off its ends.
            const anchor =
              left < 8 ? 'left-0 translate-x-0' : left > 92 ? 'right-0 translate-x-0' : 'left-1/2 -translate-x-1/2'

            return (
              <div key={attempt.id} className="absolute top-0" style={{ left: `${left}%` }}>
                {/* Attempt number, above the rail */}
                <div
                  className={`absolute -top-7 ${anchor} mono text-[11.5px] whitespace-nowrap text-mute`}
                >
                  #{attempt.attemptNumber}
                </div>

                {/* The mark itself */}
                <div
                  className={`absolute -top-[5px] -left-[5px] size-2.5 rounded-full ${style.dot} ring-3 ring-[var(--rx-surface)]`}
                  title={`Attempt ${attempt.attemptNumber} — ${style.label} at ${relativeTime(attempt.attemptedAt)}`}
                />

                {/* Outcome and timing, below the rail */}
                <div className={`absolute top-4 ${anchor} whitespace-nowrap text-center`}>
                  <div className={`mono text-[12px] ${style.text}`}>
                    {attempt.statusCode ?? 'no reply'}
                  </div>
                  <div className="mono text-[11px] text-mute">{duration(attempt.responseTimeMs)}</div>
                </div>
              </div>
            )
          })}

          {/* The backoff gaps, as a separate layer centred between consecutive marks. Drawn
              separately rather than hung off each mark so the label sits in the middle of the space
              it describes instead of beside one of its ends. */}
          {gaps.map((gap, index) => {
            const from = position(times[index], index)
            const to = position(times[index + 1], index + 1)
            const midpoint = (from + to) / 2
            const width = to - from

            // Below about 9% of the rail there is no room for "waited 4.0s" without it colliding
            // with the marks on either side.
            if (width < 9) return null

            return (
              <div
                key={`gap-${index}`}
                className="absolute -top-[19px] -translate-x-1/2 mono text-[11px] whitespace-nowrap text-amber"
                style={{ left: `${midpoint}%` }}
              >
                waited {duration(gap)}
              </div>
            )
          })}
        </div>
      </div>
    </section>
  )
}
