import { NavLink } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { api, apiKeyStore } from '../lib/api'
import { count, duration } from '../lib/format'
import { ThemeToggle } from './ThemeToggle'
import { Logo } from './Logo'

/**
 * The navigation rail.
 *
 * Narrow and fixed, with live queue telemetry pinned at its foot. The telemetry placement is the
 * deliberate part: queue depth and the age of the oldest waiting delivery are the two numbers that
 * tell you whether the system is keeping up, and they are useful on every page, not just the
 * overview. Putting them in the chrome means they are always in peripheral vision — the way a
 * readout on a piece of equipment is — rather than something you navigate to.
 */
export function NavRail({
  onDisconnect,
  onClose,
  className = '',
}: {
  onDisconnect: () => void
  onClose?: () => void
  className?: string
}) {
  return (
    <nav className={`w-[236px] shrink-0 flex-col border-r border-line bg-surface ${className}`}>
      <Brand onClose={onClose} />

      <div className="flex flex-1 flex-col gap-0.5 px-2 py-3 overflow-y-auto">
        <RailLink to="/" label="Overview" icon={<IconGauge />} onClick={onClose} />
        <RailLink to="/deliveries" label="Deliveries" icon={<IconStream />} onClick={onClose} />
        <RailLink to="/endpoints" label="Endpoints" icon={<IconTarget />} onClick={onClose} />
      </div>

      <QueueTelemetry />

      <div className="flex items-center justify-between border-t border-line px-3 py-2">
        <button
          type="button"
          onClick={() => {
            onClose?.()
            onDisconnect()
          }}
          className="rounded px-1.5 py-1 text-[12.5px] text-mute transition-colors hover:bg-surface-2 hover:text-ink"
        >
          Disconnect
        </button>
        <ThemeToggle />
      </div>
    </nav>
  )
}

function Brand({ onClose }: { onClose?: () => void }) {
  return (
    <div className="flex items-center justify-between border-b border-line px-3 py-3.5">
      <div className="flex flex-1 items-center justify-center">
        <Logo className="h-10 w-full object-contain drop-shadow-sm" />
      </div>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          className="ml-2 grid size-7 place-items-center rounded text-mute transition-colors hover:bg-surface-2 hover:text-ink md:hidden"
          aria-label="Close navigation"
        >
          <IconClose />
        </button>
      )}
    </div>
  )
}

function RailLink({
  to,
  label,
  icon,
  onClick,
}: {
  to: string
  label: string
  icon: ReactNode
  onClick?: () => void
}) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      onClick={onClick}
      className={({ isActive }) =>
        `flex items-center gap-2.5 rounded px-2.5 py-2 text-[13.5px] transition-colors ${
          isActive
            ? 'bg-surface-2 text-ink'
            : 'text-dim hover:bg-surface-2 hover:text-ink'
        }`
      }
    >
      {({ isActive }) => (
        <>
          {/* The active marker is a 2px amber bar, not a background tint alone — a tint is easy to
              miss at this contrast, and the bar reads instantly in peripheral vision. */}
          <span
            className={`-ml-2.5 h-5 w-[2px] rounded-r ${isActive ? 'bg-amber' : 'bg-transparent'}`}
            aria-hidden
          />
          <span className={isActive ? 'text-amber' : ''}>{icon}</span>
          {label}
        </>
      )}
    </NavLink>
  )
}

/**
 * Live queue readout.
 *
 * Polls rather than subscribing. A websocket would be the obvious upgrade, but polling a cheap
 * aggregate every three seconds is accurate enough for a human reading a number, needs no
 * connection lifecycle, and degrades to "slightly stale" rather than "silently frozen" when the
 * network hiccups — which is the failure mode that matters for a status readout.
 */
function QueueTelemetry() {
  const { data, isError } = useQuery({
    queryKey: ['stats'],
    queryFn: api.stats,
    refetchInterval: 3000,
    enabled: apiKeyStore.get() !== null,
  })

  const live = !isError && data !== undefined
  const behind = (data?.oldestDueAgeSec ?? 0) > 30

  return (
    <div className="border-t border-line px-4 py-3">
      <div className="mb-2 flex items-center gap-1.5">
        <span
          className={`size-1.5 rounded-full ${live ? 'bg-emerald rx-live' : 'bg-mute'}`}
          aria-hidden
        />
        <span className="text-[11.5px] text-mute">{live ? 'Live' : 'Not connected'}</span>
      </div>

      <dl className="space-y-1.5">
        <Readout label="Waiting now" value={count(data?.dueNow ?? 0)} />
        <Readout
          label="Oldest wait"
          value={duration((data?.oldestDueAgeSec ?? 0) * 1000)}
          tone={behind ? 'text-amber' : undefined}
        />
        <Readout label="Sending" value={count(data?.inProgress ?? 0)} />
      </dl>
    </div>
  )
}

function Readout({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <dt className="text-[12px] text-mute">{label}</dt>
      <dd className={`mono tabular text-[12.5px] ${tone ?? 'text-dim'}`}>{value}</dd>
    </div>
  )
}

/* Icons: drawn inline at 18px on a 20px grid so stroke weights match the type. */

export function IconMenu() {
  return (
    <svg viewBox="0 0 20 20" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.7">
      <path d="M3 5h14M3 10h14M3 15h14" strokeLinecap="round" />
    </svg>
  )
}

export function IconClose() {
  return (
    <svg viewBox="0 0 20 20" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.7">
      <path d="m4.5 4.5 11 11M15.5 4.5l-11 11" strokeLinecap="round" />
    </svg>
  )
}

function IconGauge() {
  return (
    <svg viewBox="0 0 20 20" className="size-[18px]" fill="none" stroke="currentColor" strokeWidth="1.6">
      <path d="M3.4 14.5a8 8 0 1 1 13.2 0" strokeLinecap="round" />
      <path d="M10 10.5 13.2 7.6" strokeLinecap="round" />
    </svg>
  )
}

function IconStream() {
  return (
    <svg viewBox="0 0 20 20" className="size-[18px]" fill="none" stroke="currentColor" strokeWidth="1.6">
      <path d="M3 6h9M3 10h14M3 14h6" strokeLinecap="round" />
      <circle cx="15.5" cy="6" r="1.4" />
      <circle cx="11.5" cy="14" r="1.4" />
    </svg>
  )
}

function IconTarget() {
  return (
    <svg viewBox="0 0 20 20" className="size-[18px]" fill="none" stroke="currentColor" strokeWidth="1.6">
      <circle cx="10" cy="10" r="7" />
      <circle cx="10" cy="10" r="2.6" />
    </svg>
  )
}

