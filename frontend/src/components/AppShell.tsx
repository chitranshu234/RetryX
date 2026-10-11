import type { ReactNode } from 'react'
import { NavRail } from './NavRail'
import { Footer } from './Footer'

/**
 * The page frame: rail on the left, scrolling content on the right.
 *
 * The content column scrolls while the rail does not, so the queue telemetry at the foot of the
 * rail stays visible through a long delivery list — which is the entire reason it is in the rail.
 *
 * @param props.title      page heading, shown in the content header
 * @param props.subtitle   one line of orientation under the heading
 * @param props.actions    page-level actions, right-aligned in the header
 * @param props.children   page content
 * @param props.onDisconnect clears the stored API key
 */
export function AppShell({
  title,
  subtitle,
  actions,
  children,
  onDisconnect,
}: {
  title: string
  subtitle?: string
  actions?: ReactNode
  children: ReactNode
  onDisconnect: () => void
}) {
  return (
    <div className="flex h-screen overflow-hidden bg-bg">
      <NavRail onDisconnect={onDisconnect} />

      <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-3.5">
          <div className="min-w-0">
            <h1 className="truncate text-[17px] font-semibold tracking-tight">{title}</h1>
            {subtitle && <p className="mt-0.5 text-[12.5px] text-dim">{subtitle}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>

        <div className="flex-1">
          {children}
        </div>

        <div className="px-5 pb-6">
          <Footer />
        </div>
      </main>
    </div>
  )
}
