import { useState } from 'react'
import { api, apiKeyStore } from '../lib/api'
import { PrimaryButton } from '../components/Primitives'
import { ThemeToggle } from '../components/ThemeToggle'
import { Footer } from '../components/Footer'

/** The key seeded by `docker compose up`, offered so the demo is one click rather than a hunt. */
const DEMO_KEY = 'rtx_sk_demo_0000000000000000000000'

/**
 * The connect screen, shown when no API key is stored.
 *
 * Retryx has no login — it authenticates with an API key, and there is deliberately no
 * unauthenticated endpoint that mints one, because a public key-minting endpoint would be worse
 * than having no authentication at all. So the first screen asks for a key.
 *
 * The key is verified before it is stored. Storing it first and discovering it is wrong on the next
 * page means the user is dropped into a broken dashboard with no obvious cause; checking here lets
 * the error appear next to the field that caused it.
 *
 * @param props.onConnected called once a key has been verified and stored
 */
export function Connect({ onConnected }: { onConnected: () => void }) {
  const [key, setKey] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function connect(candidate: string) {
    const trimmed = candidate.trim()
    if (!trimmed) {
      setError('Paste an API key to continue.')
      return
    }

    setBusy(true)
    setError(null)
    try {
      const valid = await api.verifyKey(trimmed)
      if (!valid) {
        setError('That key was not accepted. Check it against the key printed in the server log.')
        return
      }
      apiKeyStore.set(trimmed)
      onConnected()
    } catch {
      setError('Could not reach the Retryx API. Is the backend running on port 8080?')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg">
      <div className="flex justify-end p-4">
        <ThemeToggle />
      </div>

      <div className="flex flex-1 items-center justify-center px-6 pb-12">
        <div className="w-full max-w-[420px]">
          <div className="mb-7 flex flex-col items-start gap-3">
            <img src="/logo.png" alt="RetryX" className="h-10 w-auto object-contain" />
            <p className="text-[13px] text-dim">
              Your events, delivered to the URLs you register — and retried until they arrive.
            </p>
          </div>

          <form
            onSubmit={(event) => {
              event.preventDefault()
              void connect(key)
            }}
            className="rounded-lg border border-line bg-surface p-5"
          >
            <label htmlFor="api-key" className="block text-[13px] text-ink">
              API key
            </label>
            <p className="mt-1 text-[12.5px] text-dim">
              Retryx prints one in the server log when it starts.
            </p>

            <input
              id="api-key"
              type="password"
              value={key}
              onChange={(event) => setKey(event.target.value)}
              placeholder="rtx_sk_…"
              autoComplete="off"
              spellCheck={false}
              className="mono mt-3 w-full rounded border border-line bg-bg px-3 py-2 text-[13px] text-ink placeholder:text-mute"
            />

            {error && (
              <p className="mt-2.5 text-[12.5px] text-rose" role="alert">
                {error}
              </p>
            )}

            <div className="mt-4 flex items-center gap-3">
              <PrimaryButton type="submit" busy={busy}>
                Connect
              </PrimaryButton>
              <button
                type="button"
                onClick={() => {
                  setKey(DEMO_KEY)
                  void connect(DEMO_KEY)
                }}
                className="text-[12.5px] text-dim underline decoration-line underline-offset-3 transition-colors hover:text-ink"
              >
                Use the demo key
              </button>
            </div>
          </form>

          <p className="mt-4 text-[12px] leading-relaxed text-mute">
            The key is kept in this browser&apos;s local storage so you stay connected between
            visits. Revoke it server-side if this machine is shared.
          </p>
        </div>
      </div>

      <div className="mx-auto w-full max-w-[640px] px-6 pb-6">
        <Footer />
      </div>
    </div>
  )
}
