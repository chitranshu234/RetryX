/**
 * Formatting helpers.
 *
 * All of these exist because the same value is rendered in several places and must read identically
 * everywhere — a duration shown as "1.2s" in the table and "1200ms" on the detail page makes a
 * reader stop and convert, which is a small tax paid on every glance.
 */

/**
 * A short, human duration.
 *
 * Switches unit by magnitude rather than always using one: "340ms" and "2.1s" and "4m 12s" each
 * carry about the same amount of information for their size, where "0.34s" and "252s" do not.
 *
 * @param ms milliseconds
 * @returns e.g. `340ms`, `2.1s`, `4m 12s`, `1h 03m`
 */
export function duration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`

  const totalSeconds = Math.round(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60

  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`

  const hours = Math.floor(minutes / 60)
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`
}

/**
 * Time relative to now, in both directions.
 *
 * Future times matter here as much as past ones: a pending delivery's most important property is
 * when it will next be tried, so "in 8s" has to be as natural to render as "8s ago".
 *
 * @param iso ISO-8601 timestamp
 * @returns e.g. `just now`, `12s ago`, `in 8s`, `3h ago`
 */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—'

  const deltaMs = new Date(iso).getTime() - Date.now()
  const future = deltaMs > 0
  const seconds = Math.abs(deltaMs) / 1000

  if (seconds < 3) return 'just now'

  const [value, unit] =
    seconds < 60
      ? [Math.round(seconds), 's']
      : seconds < 3600
        ? [Math.round(seconds / 60), 'm']
        : seconds < 86_400
          ? [Math.round(seconds / 3600), 'h']
          : [Math.round(seconds / 86_400), 'd']

  return future ? `in ${value}${unit}` : `${value}${unit} ago`
}

/**
 * Absolute time, for tooltips and anywhere exactness matters more than brevity.
 *
 * @param iso ISO-8601 timestamp
 * @returns a locale-formatted date and time, with seconds
 */
export function absoluteTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * Hour label for the throughput chart.
 *
 * @param iso ISO-8601 timestamp
 * @returns e.g. `14:00`
 */
export function hourLabel(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

/**
 * Thousands-separated integer.
 *
 * @param value a count
 * @returns e.g. `12,481`
 */
export function count(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return value.toLocaleString()
}

/**
 * A long identifier, shortened for display.
 *
 * Keeps the head and the tail, because UUID prefixes are often shared (same generator, same
 * millisecond) and the distinguishing characters are frequently at the end. Truncating only the tail
 * would make two different ids look identical.
 *
 * @param id   full identifier
 * @param head characters to keep at the start
 * @param tail characters to keep at the end
 * @returns e.g. `7a1c9f2d…4e81`
 */
export function shortId(id: string | null | undefined, head = 8, tail = 4): string {
  if (!id) return '—'
  if (id.length <= head + tail + 1) return id
  return `${id.slice(0, head)}…${id.slice(-tail)}`
}

/**
 * Pretty-prints a JSON value.
 *
 * @param value parsed JSON
 * @returns indented JSON text
 */
export function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * A one-line preview of a payload, for table rows.
 *
 * @param value parsed JSON
 * @param max   characters to keep
 * @returns compact JSON, truncated
 */
export function compactJson(value: unknown, max = 80): string {
  let text: string
  try {
    text = JSON.stringify(value) ?? ''
  } catch {
    text = String(value)
  }
  return text.length > max ? `${text.slice(0, max)}…` : text
}
