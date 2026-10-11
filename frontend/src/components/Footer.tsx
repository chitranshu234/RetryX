import { useState } from 'react'

export function Footer() {
  const [authorName, setAuthorName] = useState(() => {
    try {
      return localStorage.getItem('retryx.author') || 'Chitranshu Pandey'
    } catch {
      return 'Chitranshu Pandey'
    }
  })
  const [isEditing, setIsEditing] = useState(false)

  const handleAuthorSave = (name: string) => {
    const trimmed = name.trim() || 'Chitranshu Pandey'
    setAuthorName(trimmed)
    setIsEditing(false)
    try {
      localStorage.setItem('retryx.author', trimmed)
    } catch {
      /* ignore */
    }
  }

  return (
    <footer className="mt-8 rounded-lg border border-line bg-surface p-4 font-mono text-[12.5px] sm:text-[13px] text-ink shadow-sm transition-colors">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap items-center gap-2 text-ink">
          <span className="font-medium text-amber">retryx</span>
          <span className="text-dim">·</span>
          <span className="text-dim">Webhooks that never give up.</span>
        </div>

        <div className="flex flex-wrap items-center gap-4 sm:gap-5 text-dim">
          <a
            href="https://github.com/chitranshu234/RetryX#readme"
            target="_blank"
            rel="noopener noreferrer"
            className="transition-colors hover:text-ink hover:underline"
          >
            Docs
          </a>
          <a
            href="https://github.com/chitranshu234/RetryX"
            target="_blank"
            rel="noopener noreferrer"
            className="transition-colors hover:text-ink hover:underline"
          >
            GitHub
          </a>
          <a
            href="http://localhost:8080/actuator/health"
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1.5 transition-colors hover:text-ink hover:underline"
          >
            <span className="inline-block size-2 rounded-full bg-emerald"></span>
            Status
          </a>
        </div>
      </div>

      <div className="my-3 border-t border-line/80" />

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between text-dim">
        <div className="flex flex-wrap items-center gap-2">
          <span>© 2026 RetryX</span>
          <span>·</span>
          <span>Built by</span>
          {isEditing ? (
            <input
              type="text"
              autoFocus
              defaultValue={authorName}
              placeholder="Chitranshu Pandey"
              onBlur={(e) => handleAuthorSave(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleAuthorSave((e.target as HTMLInputElement).value)
                if (e.key === 'Escape') setIsEditing(false)
              }}
              className="rounded border border-amber bg-surface-2 px-1.5 py-0.5 text-xs text-ink outline-none"
            />
          ) : (
            <span
              onClick={() => setIsEditing(true)}
              title="Click to edit name"
              className="cursor-pointer font-medium text-ink hover:text-amber hover:underline"
            >
              {authorName}
            </span>
          )}
        </div>
      </div>
    </footer>
  )
}
