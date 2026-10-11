import { useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, RetryxApiError } from '../lib/api'
import { relativeTime } from '../lib/format'
import { AppShell } from '../components/AppShell'
import { CopyButton } from '../components/CopyButton'
import { Empty, ErrorState, GhostButton, PrimaryButton, Spinner } from '../components/Primitives'
import type { CreatedEndpoint, Endpoint } from '../lib/types'

/**
 * Endpoints: add, disable, delete.
 *
 * @param props.onDisconnect clears the stored API key
 */
export function Endpoints({ onDisconnect }: { onDisconnect: () => void }) {
  const queryClient = useQueryClient()
  const [adding, setAdding] = useState(false)
  const [created, setCreated] = useState<CreatedEndpoint | null>(null)

  const endpoints = useQuery({ queryKey: ['endpoints'], queryFn: api.listEndpoints })

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['endpoints'] })
  }

  const toggle = useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => api.setEndpointActive(id, active),
    onSuccess: refresh,
  })

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteEndpoint(id),
    onSuccess: refresh,
  })

  return (
    <AppShell
      title="Endpoints"
      subtitle="The URLs Retryx delivers your events to"
      onDisconnect={onDisconnect}
      actions={
        !adding && (
          <PrimaryButton onClick={() => setAdding(true)}>Add endpoint</PrimaryButton>
        )
      }
    >
      {created && <SecretReveal created={created} onDismiss={() => setCreated(null)} />}

      {adding && (
        <AddEndpointForm
          onCancel={() => setAdding(false)}
          onCreated={(result) => {
            setCreated(result)
            setAdding(false)
            refresh()
          }}
        />
      )}

      <section className="px-5 py-4">
        {endpoints.isError ? (
          <ErrorState error={endpoints.error} retry={() => void endpoints.refetch()} />
        ) : endpoints.isLoading ? (
          <div className="rx-sweep relative h-24 overflow-hidden rounded-lg bg-surface-2" />
        ) : (endpoints.data ?? []).length === 0 ? (
          <div className="rounded-lg border border-line">
            <Empty
              title="No endpoints yet"
              body="Add the URL you want your events delivered to. Retryx will sign every request and keep retrying until the receiver accepts it."
              action={<PrimaryButton onClick={() => setAdding(true)}>Add endpoint</PrimaryButton>}
            />
          </div>
        ) : (
          <ul className="space-y-2.5">
            {endpoints.data!.map((endpoint) => (
              <EndpointRow
                key={endpoint.id}
                endpoint={endpoint}
                busy={toggle.isPending || remove.isPending}
                onToggle={() => toggle.mutate({ id: endpoint.id, active: !endpoint.active })}
                onDelete={() => remove.mutate(endpoint.id)}
              />
            ))}
          </ul>
        )}
      </section>
    </AppShell>
  )
}

function EndpointRow({
  endpoint,
  busy,
  onToggle,
  onDelete,
}: {
  endpoint: Endpoint
  busy: boolean
  onToggle: () => void
  onDelete: () => void
}) {
  const [confirming, setConfirming] = useState(false)

  return (
    <li className="rounded-lg border border-line bg-surface">
      <div className="flex flex-wrap items-start justify-between gap-4 px-4 py-3.5">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <span
              className={`size-2 shrink-0 rounded-full ${endpoint.active ? 'bg-emerald' : 'bg-mute'}`}
              aria-hidden
            />
            <h3 className="truncate text-[14.5px]">{endpoint.name}</h3>
            {!endpoint.active && (
              <span className="rounded bg-surface-2 px-1.5 py-0.5 text-[11.5px] text-dim">
                Not receiving events
              </span>
            )}
          </div>

          <div className="mono mt-1.5 flex items-center gap-1 text-[12.5px] text-dim">
            <span className="truncate">{endpoint.url}</span>
            <CopyButton value={endpoint.url} label="Copy URL" />
          </div>

          {endpoint.description && (
            <p className="mt-1.5 text-[12.5px] text-mute">{endpoint.description}</p>
          )}

          <dl className="mt-2.5 flex flex-wrap gap-x-6 gap-y-1 text-[11.5px]">
            <Meta label="Signing secret" value={endpoint.secretPreview} mono />
            <Meta label="Retry budget" value={`${endpoint.maxAttempts ?? 6} attempts`} />
            <Meta label="Added" value={relativeTime(endpoint.createdAt)} />
          </dl>
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <GhostButton onClick={onToggle} disabled={busy}>
            {endpoint.active ? 'Disable' : 'Enable'}
          </GhostButton>

          {confirming ? (
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                onClick={onDelete}
                disabled={busy}
                className="rounded bg-rose px-2.5 py-1.5 text-[12.5px] font-medium text-bg transition-opacity hover:opacity-90"
              >
                {busy ? <Spinner className="size-3.5" /> : 'Delete'}
              </button>
              <button
                type="button"
                onClick={() => setConfirming(false)}
                className="rounded px-2 py-1.5 text-[12.5px] text-dim hover:text-ink"
              >
                Keep
              </button>
            </div>
          ) : (
            <button
              type="button"
              onClick={() => setConfirming(true)}
              className="rounded border border-line px-2.5 py-1.5 text-[12.5px] text-dim transition-colors hover:border-rose hover:text-rose"
            >
              Delete
            </button>
          )}
        </div>
      </div>

      {confirming && (
        <p className="border-t border-line-soft px-4 py-2 text-[12.5px] text-dim">
          Deleting stops new events and hides the endpoint. Deliveries already sent stay in the
          history so you can still see what happened.
        </p>
      )}
    </li>
  )
}

function Meta({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-mute">{label}</dt>
      <dd className={mono ? 'mono text-dim' : 'text-dim'}>{value}</dd>
    </div>
  )
}

/**
 * The create form.
 *
 * Only three fields. The retry budget is optional and the signing secret is not offered at all —
 * Retryx generates one, because a generated 32-byte secret is better than any secret a person will
 * type into a form, and offering the field invites someone to reuse a password.
 */
function AddEndpointForm({
  onCancel,
  onCreated,
}: {
  onCancel: () => void
  onCreated: (created: CreatedEndpoint) => void
}) {
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [description, setDescription] = useState('')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () =>
      api.createEndpoint({
        name: name.trim(),
        url: url.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: onCreated,
    onError: (failure) => {
      // The SSRF guard's rejection reason is genuinely actionable ("port 6379 is not allowed"), so
      // it is shown verbatim rather than replaced with a generic message.
      setError(
        failure instanceof RetryxApiError
          ? failure.message
          : 'Could not add the endpoint. Check the URL and try again.',
      )
    },
  })

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        setError(null)
        create.mutate()
      }}
      className="border-b border-line bg-surface px-5 py-4"
    >
      <h2 className="text-[15px]">Add an endpoint</h2>
      <p className="mt-0.5 text-[12.5px] text-dim">
        Retryx signs every request with a secret it generates for this endpoint, and shows you the
        secret once.
      </p>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <Field label="Name" hint="How you will recognise it">
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={120}
            placeholder="Orders service"
            className="w-full rounded border border-line bg-bg px-2.5 py-1.5 text-[13px] placeholder:text-mute"
          />
        </Field>

        <Field label="URL" hint="Where the POST goes">
          <input
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            required
            type="url"
            placeholder="https://api.example.com/webhooks/retryx"
            className="mono w-full rounded border border-line bg-bg px-2.5 py-1.5 text-[12.5px] placeholder:text-mute"
          />
        </Field>

        <Field label="Note" hint="Optional" className="sm:col-span-2">
          <input
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={500}
            placeholder="Owned by the payments team"
            className="w-full rounded border border-line bg-bg px-2.5 py-1.5 text-[13px] placeholder:text-mute"
          />
        </Field>
      </div>

      {error && (
        <p className="mt-3 text-[12.5px] text-rose" role="alert">
          {error}
        </p>
      )}

      <div className="mt-4 flex items-center gap-2">
        <PrimaryButton type="submit" busy={create.isPending}>
          Add endpoint
        </PrimaryButton>
        <GhostButton type="button" onClick={onCancel}>
          Cancel
        </GhostButton>
      </div>
    </form>
  )
}

function Field({
  label,
  hint,
  children,
  className = '',
}: {
  label: string
  hint?: string
  children: ReactNode
  className?: string
}) {
  return (
    <label className={`block ${className}`}>
      <span className="text-[12.5px] text-ink">{label}</span>
      {hint && <span className="ml-2 text-[11.5px] text-mute">{hint}</span>}
      <div className="mt-1">{children}</div>
    </label>
  )
}

/**
 * The show-once secret.
 *
 * Given its own prominent band rather than a toast, because it cannot be recovered. A notification
 * that fades after four seconds is the wrong container for the only copy of a credential.
 */
function SecretReveal({
  created,
  onDismiss,
}: {
  created: CreatedEndpoint
  onDismiss: () => void
}) {
  return (
    <section className="border-b border-line bg-amber-soft px-5 py-4">
      <h2 className="text-[14.5px] text-ink">
        Copy the signing secret for {created.endpoint.name}
      </h2>
      <p className="mt-1 max-w-2xl text-[12.5px] text-dim">
        This is the only time Retryx will show it. Your receiver needs it to verify that a request
        really came from Retryx. If you lose it, delete the endpoint and add it again.
      </p>

      <div className="mt-3 flex items-center gap-2 rounded border border-line bg-surface px-3 py-2">
        <code className="mono flex-1 overflow-x-auto text-[13px] whitespace-nowrap text-ink">
          {created.signingSecret}
        </code>
        <CopyButton value={created.signingSecret} label="Copy signing secret" />
      </div>

      <GhostButton onClick={onDismiss} className="mt-3">
        I have saved it
      </GhostButton>
    </section>
  )
}
