import { useEffect, useState } from 'react'

type Theme = 'dark' | 'light'

const STORAGE_KEY = 'retryx.theme'

/**
 * Switches between the two themes.
 *
 * The theme is stored and applied as a `data-theme` attribute on `<html>`, and `index.html` reads
 * it in a blocking script before first paint. That ordering is the whole point: applying the theme
 * from React means the page renders once in the default theme and then repaints, which is the
 * flash-of-wrong-theme that makes a reload feel broken.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(
    () => (document.documentElement.dataset.theme as Theme) ?? 'dark',
  )

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    try {
      localStorage.setItem(STORAGE_KEY, theme)
    } catch {
      /* storage disabled; the choice lasts for this page */
    }
  }, [theme])

  const next = theme === 'dark' ? 'light' : 'dark'

  return (
    <button
      type="button"
      onClick={() => setTheme(next)}
      title={`Switch to ${next} theme`}
      aria-label={`Switch to ${next} theme`}
      className="grid size-7 place-items-center rounded text-mute transition-colors hover:bg-surface-2 hover:text-ink"
    >
      {theme === 'dark' ? (
        <svg viewBox="0 0 20 20" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5">
          <circle cx="10" cy="10" r="3.4" />
          <path
            d="M10 2.6v1.8M10 15.6v1.8M2.6 10h1.8M15.6 10h1.8M4.8 4.8l1.3 1.3M13.9 13.9l1.3 1.3M15.2 4.8l-1.3 1.3M6.1 13.9l-1.3 1.3"
            strokeLinecap="round"
          />
        </svg>
      ) : (
        <svg viewBox="0 0 20 20" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path
            d="M16 12.4A6.8 6.8 0 0 1 7.6 4a7 7 0 1 0 8.4 8.4Z"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </button>
  )
}
