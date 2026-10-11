import type { AttemptOutcome, DeliveryStatus } from '../lib/types'

/**
 * The visual vocabulary for delivery state.
 *
 * Defined once, in one object, because consistency of meaning is the whole job: if "pending" is
 * amber in the table and grey in the detail header, the colour has stopped carrying information and
 * become decoration. Every status mark in the application reads from here.
 *
 * Status is rendered as a coloured dot plus a word — never as a filled pill. Filled status pills
 * compete with actual buttons for attention, and in a dense table they turn a scannable column into
 * a row of blocks. A dot is enough to find a colour; the word is there because colour alone fails
 * for about one in twelve men, and a dashboard whose only signal is red-versus-green is unusable for
 * them.
 */
export const STATUS_PRESENTATION: Record<
  DeliveryStatus,
  { label: string; dot: string; text: string; bar: string; soft: string; live?: boolean }
> = {
  DELIVERED: {
    label: 'Delivered',
    dot: 'bg-emerald',
    text: 'text-emerald',
    bar: 'bg-emerald',
    soft: 'bg-emerald-soft',
  },
  PENDING: {
    label: 'Waiting to retry',
    dot: 'bg-amber',
    text: 'text-amber',
    bar: 'bg-amber',
    soft: 'bg-amber-soft',
  },
  IN_PROGRESS: {
    label: 'Sending',
    dot: 'bg-sky',
    text: 'text-sky',
    bar: 'bg-sky',
    soft: 'bg-sky-soft',
    live: true,
  },
  FAILED: {
    label: 'Gave up',
    dot: 'bg-rose',
    text: 'text-rose',
    bar: 'bg-rose',
    soft: 'bg-rose-soft',
  },
}

/**
 * The same vocabulary for a single attempt.
 *
 * Attempt outcomes are worded from the receiver's point of view ("receiver failed", "receiver
 * refused") rather than the sender's, because when someone opens this page they are trying to work
 * out whose fault it is, and the words should answer that rather than restate the enum.
 */
export const OUTCOME_PRESENTATION: Record<
  AttemptOutcome,
  { label: string; dot: string; text: string }
> = {
  SUCCESS: { label: 'Accepted', dot: 'bg-emerald', text: 'text-emerald' },
  RETRYABLE_ERROR: { label: 'Receiver failed', dot: 'bg-amber', text: 'text-amber' },
  PERMANENT_ERROR: { label: 'Receiver refused', dot: 'bg-rose', text: 'text-rose' },
  BLOCKED: { label: 'Blocked by Retryx', dot: 'bg-rose', text: 'text-rose' },
}

/**
 * A delivery's status, as a dot and a word.
 *
 * @param props.status the delivery status
 * @param props.compact drop the word, for very narrow columns
 */
export function StatusMark({ status, compact = false }: { status: DeliveryStatus; compact?: boolean }) {
  const style = STATUS_PRESENTATION[status]

  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap">
      <span
        className={`size-2 shrink-0 rounded-full ${style.dot} ${style.live ? 'rx-live' : ''}`}
        aria-hidden
      />
      {!compact && <span className={`text-[13px] ${style.text}`}>{style.label}</span>}
      {compact && <span className="sr-only">{style.label}</span>}
    </span>
  )
}

/**
 * An attempt's outcome, as a dot and a word.
 *
 * @param props.outcome the attempt outcome
 */
export function OutcomeMark({ outcome }: { outcome: AttemptOutcome }) {
  const style = OUTCOME_PRESENTATION[outcome]

  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap">
      <span className={`size-2 shrink-0 rounded-full ${style.dot}`} aria-hidden />
      <span className={`text-[13px] ${style.text}`}>{style.label}</span>
    </span>
  )
}

/**
 * An HTTP status code, coloured by class.
 *
 * Monospace because it is a machine value and because three-digit codes in a column should line up.
 *
 * @param props.code the status code, or null when no response arrived
 */
export function StatusCode({ code }: { code: number | null }) {
  if (code === null) {
    return <span className="mono text-mute">no reply</span>
  }

  const tone =
    code < 300 ? 'text-emerald' : code < 400 ? 'text-dim' : code < 500 ? 'text-rose' : 'text-amber'

  return <span className={`mono ${tone}`}>{code}</span>
}
