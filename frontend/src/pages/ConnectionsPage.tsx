import { useState } from 'react'
import { Link, useNavigate } from 'react-router'

import { useConnections, useDeleteConnection, useTestConnection } from '../api/connections'
import { ApiError } from '../api/client'
import type { ConnectionProfile } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ChevronRightIcon, PlusIcon } from '../components/icons'
import {
  cardClass,
  EmptyState,
  ErrorBanner,
  ProviderBadge,
  Skeleton,
  buttonClass,
  secondaryButtonClass,
} from '../components/Primitives'
import { useToast } from '../components/ToastProvider'

function describeTarget(profile: ConnectionProfile): string {
  // Kafka first: it has no host or port at all, so the fallback below would render "—:—".
  if (profile.bootstrapServers) {
    return profile.bootstrapServers
  }
  if (profile.brokerUrlOverride) {
    return profile.brokerUrlOverride
  }
  const hostPort = `${profile.host ?? '—'}:${profile.port ?? '—'}`
  return profile.queueManagerName ? `${hostPort} · ${profile.queueManagerName}` : hostPort
}

export default function ConnectionsPage() {
  const navigate = useNavigate()
  const toast = useToast()
  const { data, isPending, isError, error, refetch } = useConnections()
  const deleteConnection = useDeleteConnection()
  const testConnection = useTestConnection()

  const [pendingDelete, setPendingDelete] = useState<ConnectionProfile | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)

  const runTest = (profile: ConnectionProfile) => {
    setTestingId(profile.id)
    testConnection.mutate(profile.id, {
      onSuccess: (result) => {
        if (result.success) {
          toast.success(`${profile.name}: connected in ${result.durationMs} ms.`)
        } else {
          toast.error(`${profile.name}: ${result.message}`)
        }
      },
      onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
      onSettled: () => setTestingId(null),
    })
  }

  const confirmDelete = () => {
    if (!pendingDelete) return
    const name = pendingDelete.name
    deleteConnection.mutate(pendingDelete.id, {
      onSuccess: () => {
        toast.success(`Deleted connection "${name}".`)
        setPendingDelete(null)
      },
      onError: (err) => {
        toast.error(err instanceof ApiError ? err.message : String(err))
        setPendingDelete(null)
      },
    })
  }

  return (
    <div className="space-y-6">
      <div className={`hero-surface overflow-hidden ${cardClass}`}>
        <div className="hero-grid flex flex-wrap items-center justify-between gap-4 px-5 py-6">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-fg">Connections</h1>
            <p className="mt-1.5 max-w-xl text-sm leading-relaxed text-fg-muted">
              Saved brokers. Passwords are encrypted on disk and never sent back to this page.
            </p>
          </div>
          <Link to="/connections/new" className={buttonClass}>
            <PlusIcon size={16} /> New connection
          </Link>
        </div>
      </div>

      {isPending && <Skeleton rows={3} />}

      {isError && (
        <ErrorBanner
          title="Could not load connections"
          message={error instanceof ApiError ? error.message : String(error)}
          code={error instanceof ApiError ? error.code : undefined}
          onRetry={() => void refetch()}
        />
      )}

      {data && data.length === 0 && (
        <EmptyState
          title="No connections yet"
          body="Add a broker to start sending and browsing messages. Nothing is contacted until you ask."
          action={
            <Link to="/connections/new" className={buttonClass}>
              Add your first connection
            </Link>
          }
        />
      )}

      {data && data.length > 0 && (
        <div className={`overflow-hidden ${cardClass}`}>
          <table className="w-full text-left text-sm">
            <thead className="border-b border-line bg-surface-2/70 text-xs font-medium uppercase tracking-wide text-fg-subtle">
              <tr>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Provider</th>
                <th className="px-4 py-3 font-medium">Target</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.map((profile) => (
                <tr
                  key={profile.id}
                  onClick={() => void navigate(`/connections/${profile.id}/queue`)}
                  className="group cursor-pointer transition-colors duration-150 hover:bg-hover/70"
                >
                  <td className="px-4 py-3">
                    {/* A real Link so keyboard users can reach it, while the whole row handles the mouse.
                        stopPropagation avoids navigating twice on a direct name click. */}
                    <Link
                      to={`/connections/${profile.id}/queue`}
                      onClick={(event) => event.stopPropagation()}
                      className="font-medium text-fg transition-colors group-hover:text-brand-700"
                    >
                      {profile.name}
                    </Link>
                    {!profile.credentialsReadable && (
                      <div className="mt-0.5 text-xs text-amber-700">
                        Stored password cannot be decrypted — edit and re-enter it.
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <ProviderBadge provider={profile.provider} label={profile.providerLabel} />
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-fg-muted">
                    {describeTarget(profile)}
                  </td>
                  <td className="px-4 py-3">
                    {/* The row is the primary click; these actions must not trigger it, so the cell
                        stops the click from bubbling up to the row. */}
                    <div
                      className="flex items-center justify-end gap-2"
                      onClick={(event) => event.stopPropagation()}
                    >
                      <button
                        type="button"
                        className={secondaryButtonClass}
                        disabled={testingId === profile.id}
                        onClick={() => runTest(profile)}
                      >
                        {testingId === profile.id ? 'Testing…' : 'Test'}
                      </button>
                      <button
                        type="button"
                        className={secondaryButtonClass}
                        onClick={() => void navigate(`/connections/${profile.id}/edit`)}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className={secondaryButtonClass}
                        onClick={() => setPendingDelete(profile)}
                      >
                        Delete
                      </button>
                      <button
                        type="button"
                        onClick={() => void navigate(`/connections/${profile.id}/queue`)}
                        aria-label={`Open ${profile.name}`}
                        title="Open"
                        className="ml-1 grid h-9 w-9 place-items-center rounded-lg text-fg-subtle transition-colors hover:bg-brand-50 hover:text-brand-600 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand-500/20"
                      >
                        <ChevronRightIcon size={18} />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete this connection?"
        body={
          <>
            <strong>{pendingDelete?.name}</strong> and its stored credentials will be removed. Messages
            on the broker are not affected.
          </>
        }
        confirmLabel="Delete connection"
        busy={deleteConnection.isPending}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  )
}
