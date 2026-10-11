import { useState } from 'react'
import type { ReactNode } from 'react'

/**
 * Copies a value to the clipboard and says so.
 *
 * Every id, signature and payload in this dashboard exists to be pasted somewhere else — into a
 * log search, a curl command, a colleague's message. Selecting a truncated monospace id by hand is
 * error-prone, so anything worth copying gets one of these.
 *
 * The confirmation is the label changing to "Copied", not a toast. The feedback belongs where the
 * action happened; a notification in the corner of the screen for something the user is already
 * looking at is misdirection.
 *
 * @param props.value the full value to copy, which may differ from what is displayed
 * @param props.label accessible label describing what is being copied
 * @param props.children what to render; defaults to a copy glyph
 */
export function CopyButton({
  value,
  label = 'Copy',
  children,
}: {
  value: string
  label?: string
  children?: ReactNode
}) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
    } catch {
      // Clipboard access needs a secure context; over plain http on a remote host it is blocked.
      // Falling back to a selection-based copy keeps the button working there.
      const area = document.createElement('textarea')
      area.value = value
      area.style.position = 'fixed'
      area.style.opacity = '0'
      document.body.appendChild(area)
      area.select()
      document.execCommand('copy')
      document.body.removeChild(area)
    }
    setCopied(true)
    window.setTimeout(() => setCopied(false), 1400)
  }

  return (
    <button
      type="button"
      onClick={copy}
      title={copied ? 'Copied' : label}
      aria-label={copied ? 'Copied' : label}
      className="inline-flex items-center gap-1.5 rounded px-1.5 py-0.5 text-[12px] text-mute transition-colors hover:bg-surface-2 hover:text-ink"
    >
      {children ?? (
        <svg viewBox="0 0 16 16" className="size-3.5" fill="none" stroke="currentColor" strokeWidth="1.5">
          {copied ? (
            <path d="M3 8.5 6.2 11.5 13 4.5" strokeLinecap="round" strokeLinejoin="round" />
          ) : (
            <>
              <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
              <path d="M10.5 3.5H3.5a1 1 0 0 0-1 1v7" strokeLinecap="round" />
            </>
          )}
        </svg>
      )}
      {copied && <span>Copied</span>}
    </button>
  )
}

/**
 * A monospace identifier, truncated for display, copyable in full.
 *
 * @param props.id        the full identifier
 * @param props.display   what to show; defaults to the full id
 * @param props.label     accessible description, e.g. "Copy delivery id"
 */
export function IdChip({ id, display, label }: { id: string; display?: string; label?: string }) {
  return (
    <span className="group inline-flex items-center gap-1">
      <span className="mono text-[12.5px] text-dim" title={id}>
        {display ?? id}
      </span>
      <span className="opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <CopyButton value={id} label={label ?? 'Copy id'} />
      </span>
    </span>
  )
}
