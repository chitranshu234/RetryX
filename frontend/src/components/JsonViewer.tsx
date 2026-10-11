import { useState } from 'react'
import { prettyJson } from '../lib/format'
import { CopyButton } from './CopyButton'

/**
 * A JSON payload, shown as formatted text.
 *
 * Deliberately not a collapsible tree. The payloads here are small notification bodies, and what a
 * developer wants from this panel is to read it, copy it, and paste it into a reproduction — all of
 * which a tree view makes harder. Long payloads get a height cap with an explicit expand, so one
 * large event cannot push the attempt timeline off the screen.
 *
 * @param props.value    parsed JSON
 * @param props.title    panel heading
 * @param props.maxLines lines shown before the expand control appears
 */
export function JsonViewer({
  value,
  title = 'Payload',
  maxLines = 16,
}: {
  value: unknown
  title?: string
  maxLines?: number
}) {
  const [expanded, setExpanded] = useState(false)
  const text = prettyJson(value)
  const lines = text.split('\n')
  const clipped = !expanded && lines.length > maxLines
  const shown = clipped ? lines.slice(0, maxLines).join('\n') : text

  return (
    <div className="overflow-hidden rounded-lg border border-line bg-surface">
      <div className="flex items-center justify-between border-b border-line-soft px-3 py-2">
        <span className="text-[13px] text-dim">{title}</span>
        <div className="flex items-center gap-1">
          <span className="mono text-[11.5px] text-mute">{lines.length} lines</span>
          <CopyButton value={text} label="Copy payload" />
        </div>
      </div>

      <pre className="overflow-x-auto px-3 py-2.5 text-[12.5px] leading-relaxed text-ink">
        <code>{shown}</code>
        {clipped && <span className="text-mute">{`\n…`}</span>}
      </pre>

      {lines.length > maxLines && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="w-full border-t border-line-soft py-1.5 text-[12.5px] text-dim transition-colors hover:bg-surface-2 hover:text-ink"
        >
          {expanded ? 'Show less' : `Show all ${lines.length} lines`}
        </button>
      )}
    </div>
  )
}
