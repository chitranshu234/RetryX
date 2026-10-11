/** Small shared UI primitives: loading, empty and error states. */

import type { ButtonHTMLAttributes, ReactNode } from 'react'

/** A spinner sized to the current text. */
export function Spinner({ className = '' }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      className={`size-4 animate-spin ${className}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      aria-hidden
    >
      <circle cx="12" cy="12" r="9" className="opacity-20" />
      <path d="M21 12a9 9 0 0 0-9-9" strokeLinecap="round" />
    </svg>
  )
}

/** Rows of shimmer, sized to the table they stand in for. */
export function SkeletonRows({ rows = 6, cols = 5 }: { rows?: number; cols?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, rowIndex) => (
        <tr key={rowIndex} className="border-b border-line-soft">
          {Array.from({ length: cols }).map((__, colIndex) => (
            <td key={colIndex} className="px-4 py-3">
              <div className="rx-sweep relative h-3 overflow-hidden rounded bg-surface-2" />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

/**
 * An empty state.
 *
 * Every one of these says what to do next, not just that there is nothing here. An empty screen is
 * the one moment a new user is guaranteed to read the interface, so it is the best place to tell
 * them how to start.
 *
 * @param props.title   what is empty
 * @param props.body    what to do about it
 * @param props.action  optional call to action
 */
export function Empty({
  title,
  body,
  action,
}: {
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
      <div className="grid size-10 place-items-center rounded-full border border-line text-mute">
        <svg viewBox="0 0 20 20" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M3 10a7 7 0 1 1 2.05 4.95" strokeLinecap="round" />
          <path d="M3 6.5V10h3.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
      <h3 className="text-[15px] text-ink">{title}</h3>
      <p className="max-w-sm text-[13px] leading-relaxed text-dim">{body}</p>
      {action}
    </div>
  )
}

/**
 * An error state that names the problem and offers the fix.
 *
 * @param props.error the thrown error
 * @param props.retry optional retry handler
 */
export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong.'

  return (
    <div className="flex flex-col items-center gap-3 px-6 py-16 text-center">
      <div className="grid size-10 place-items-center rounded-full bg-rose-soft text-rose">
        <svg viewBox="0 0 20 20" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.6">
          <path d="M10 6.5v5M10 14h.01" strokeLinecap="round" />
          <circle cx="10" cy="10" r="7.2" />
        </svg>
      </div>
      <h3 className="text-[15px] text-ink">Could not load this</h3>
      <p className="max-w-md text-[13px] leading-relaxed text-dim">{message}</p>
      {retry && (
        <button
          type="button"
          onClick={retry}
          className="rounded border border-line px-3 py-1.5 text-[13px] text-ink transition-colors hover:bg-surface-2"
        >
          Try again
        </button>
      )}
    </div>
  )
}

/**
 * The primary action button. Amber-filled, and the only amber-filled control in the interface —
 * which is why it is reserved for the action this product is named after.
 */
export function PrimaryButton({
  children,
  busy = false,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return (
    <button
      {...props}
      disabled={props.disabled || busy}
      className={`inline-flex items-center gap-2 rounded bg-amber px-3 py-1.5 text-[13px] font-medium text-bg transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40 ${props.className ?? ''}`}
    >
      {busy && <Spinner className="size-3.5" />}
      {children}
    </button>
  )
}

/** A quiet secondary action. */
export function GhostButton({
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...props}
      className={`inline-flex items-center gap-2 rounded border border-line px-3 py-1.5 text-[13px] text-ink transition-colors hover:bg-surface-2 disabled:cursor-not-allowed disabled:opacity-40 ${props.className ?? ''}`}
    >
      {children}
    </button>
  )
}
