import { useState } from 'react'

export function Footer() {
  const [copied, setCopied] = useState(false)
  const [authorName, setAuthorName] = useState(() => {
    try {
      return localStorage.getItem('retryx.author') || '[Your Name]'
    } catch {
      return '[Your Name]'
    }
  })
  const [isEditing, setIsEditing] = useState(false)

  const handleCopy = () => {
    const textToCopy = `curl -X POST http://localhost:8080/api/events \\\n  -H "X-Api-Key: rtx_sk_demo_0000000000000000000000" \\\n  -H "Content-Type: application/json" \\\n  -H "Idempotency-Key: demo-1" \\\n  -d '{"eventType":"order.created","payload":{"orderId":"A-1","total":42.50}}'`
    void navigator.clipboard.writeText(textToCopy)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleAuthorSave = (name: string) => {
    const trimmed = name.trim() || '[Your Name]'
    setAuthorName(trimmed)
    setIsEditing(false)
    try {
      localStorage.setItem('retryx.author', trimmed)
    } catch {
      /* ignore */
    }
  }

  return (
    <footer className="mt-8 rounded-lg border border-line bg-surface p-4 font-mono text-[13px] text-ink shadow-sm transition-colors">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 text-ink">
          <span className="font-medium text-amber">retryx</span>
          <span className="text-dim">·</span>
          <span className="text-dim">Webhooks that never give up.</span>
        </div>

        <div className="flex items-center gap-5 text-dim">
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

          <button
            type="button"
            onClick={handleCopy}
            title={copied ? 'Copied curl demo command!' : 'Copy demo command'}
            className="relative flex items-center justify-center rounded p-1 text-dim transition-colors hover:bg-surface-2 hover:text-ink"
            aria-label="Copy demo command"
          >
            {copied ? (
              <span className="text-xs text-emerald">Copied!</span>
            ) : (
              <svg
                viewBox="0 0 24 24"
                className="size-4"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
              </svg>
            )}
          </button>
        </div>
      </div>

      <div className="my-3 border-t border-line/80" />

      <div className="flex items-center justify-between text-dim">
        <div className="flex items-center gap-2">
          <span>© 2026 RetryX</span>
          <span>·</span>
          <span>Built by</span>
          {isEditing ? (
            <input
              type="text"
              autoFocus
              defaultValue={authorName === '[Your Name]' ? '' : authorName}
              placeholder="Your Name"
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
              className="cursor-pointer text-ink hover:text-amber hover:underline"
            >
              {authorName}
            </span>
          )}
        </div>
      </div>
    </footer>
  )
}
