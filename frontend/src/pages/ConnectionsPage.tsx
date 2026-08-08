import { useState } from 'react'
import { Link, useNavigate } from 'react-router'

import { useConnections, useDeleteConnection, useTestConnection } from '../api/connections'
import { ApiError } from '../api/client'
import type { ConnectionProfile } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import {
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
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Connections</h1>
          <p className="mt-1 text-sm text-slate-600">
            Saved brokers. Passwords are encrypted on disk and never sent back to this page.
          </p>
        </div>
        <Link to="/connections/new" className={buttonClass}>
          New connection
        </Link>
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
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Provider</th>
                <th className="px-4 py-3 font-medium">Target</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.map((profile) => (
                <tr key={profile.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <div className="font-medium text-slate-900">{profile.name}</div>
                    {!profile.credentialsReadable && (
                      <div className="mt-0.5 text-xs text-amber-700">
                        Stored password cannot be decrypted — edit and re-enter it.
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <ProviderBadge provider={profile.provider} label={profile.providerLabel} />
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-slate-600">
                    {describeTarget(profile)}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-2">
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
                      <Link to={`/connections/${profile.id}/queue`} className={buttonClass}>
                        Open
                      </Link>
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
