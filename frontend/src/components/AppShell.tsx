import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { NavRail, IconMenu } from './NavRail'
import { Logo } from './Logo'
import { ThemeToggle } from './ThemeToggle'
import { Footer } from './Footer'

/**
 * The page frame: rail on the left on desktop, top bar + slide-over drawer on mobile.
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
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-bg md:flex-row">
      {/* Desktop sidebar */}
      <NavRail onDisconnect={onDisconnect} className="hidden md:flex" />

      {/* Mobile top bar */}
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-line bg-surface px-4 md:hidden">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => setMobileMenuOpen(true)}
            className="grid size-9 place-items-center rounded border border-line bg-surface-2 text-ink transition-colors hover:border-dim active:scale-95"
            aria-label="Open navigation menu"
          >
            <IconMenu />
          </button>
          <Link to="/" className="flex items-center">
            <Logo className="h-8 max-w-[130px] object-contain" />
          </Link>
        </div>

        <div className="flex items-center gap-2">
          <ThemeToggle />
        </div>
      </header>

      {/* Mobile slide-over drawer */}
      {mobileMenuOpen && (
        <div className="fixed inset-0 z-50 flex md:hidden" role="dialog" aria-modal="true">
          <div
            className="fixed inset-0 bg-black/60 backdrop-blur-sm transition-opacity"
            onClick={() => setMobileMenuOpen(false)}
            aria-hidden="true"
          />
          <div className="relative z-10 flex h-full animate-in slide-in-from-left duration-200">
            <NavRail
              onDisconnect={onDisconnect}
              onClose={() => setMobileMenuOpen(false)}
              className="flex h-full w-[280px] max-w-[85vw] shadow-2xl"
            />
          </div>
        </div>
      )}

      {/* Main scrolling content area */}
      <main className="flex min-w-0 flex-1 flex-col overflow-y-auto">
        <header className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-4 py-3 sm:px-5 sm:py-3.5">
          <div className="min-w-0">
            <h1 className="truncate text-[16.5px] sm:text-[17px] font-semibold tracking-tight">{title}</h1>
            {subtitle && <p className="mt-0.5 text-[12px] sm:text-[12.5px] text-dim">{subtitle}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
        </header>

        <div className="min-w-0 flex-1">
          {children}
        </div>

        <div className="px-4 pb-6 sm:px-5">
          <Footer />
        </div>
      </main>
    </div>
  )
}

