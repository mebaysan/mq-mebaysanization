import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { ApiError } from '../api/client'
import { useConnection } from '../api/connections'
import { useDeleteMessage, useDepth, useMessages, usePurgeQueue } from '../api/queue'
import type { QueueMessage } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import {
  EmptyState,
  ErrorBanner,
  ProviderBadge,
  Skeleton,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from '../components/Primitives'
import { useToast } from '../components/ToastProvider'
import { MessageRow } from '../features/queue/MessageRow'
import { DEFAULT_CAVEAT, PROVIDER_CAVEATS } from '../features/queue/ProviderCaveats'
import { SendPanel } from '../features/queue/SendPanel'

const BROWSE_LIMIT = 100

export default function QueueExplorerPage() {
  const params = useParams()
  const connectionId = Number(params.id)
  const toast = useToast()

  // The active queue lives in the URL, so a deep link or a refresh keeps working.
  const [searchParams, setSearchParams] = useSearchParams()
  const activeQueue = searchParams.get('queue') ?? ''
  const [queueInput, setQueueInput] = useState(activeQueue)

  const connection = useConnection(connectionId)
  const hasQueue = activeQueue.trim() !== ''

  const depth = useDepth(connectionId, activeQueue, hasQueue)
  const messages = useMessages(connectionId, activeQueue, BROWSE_LIMIT, hasQueue)
  const purge = usePurgeQueue(connectionId, activeQueue)
  const deleteMessage = useDeleteMessage(connectionId, activeQueue)

  const [pendingDelete, setPendingDelete] = useState<QueueMessage | null>(null)
  const [confirmPurge, setConfirmPurge] = useState(false)

  // Never null: a blank noun would render as a missing word rather than as a loading state.
  const caveats = connection.data ? PROVIDER_CAVEATS[connection.data.provider] : DEFAULT_CAVEAT

  const openQueue = (event: React.FormEvent) => {
    event.preventDefault()
    const trimmed = queueInput.trim()
    if (trimmed === '') return
    setSearchParams({ queue: trimmed })
  }

  const runPurge = () => {
    purge.mutate(undefined, {
      onSuccess: (result) => {
        // Never say "the queue is empty" when a cap stopped the purge early.
        const message = result.note ? `${result.message} ${result.note}` : result.message
        if (result.complete) {
          toast.success(message)
        } else {
          toast.info(message)
        }
        setConfirmPurge(false)
      },
      onError: (err) => {
        toast.error(err instanceof ApiError ? err.message : String(err))
        setConfirmPurge(false)
      },
    })
  }

  const runDelete = () => {
    if (!pendingDelete) return
    deleteMessage.mutate(pendingDelete.messageId, {
      onSuccess: (result) =>
        result.deleted ? toast.success(result.message) : toast.info(result.message),
      onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
      onSettled: () => setPendingDelete(null),
    })
  }

  if (connection.isPending) {
    return <Skeleton rows={4} />
  }

  if (connection.isError) {
    return (
      <ErrorBanner
        title="Could not load this connection"
        message={
          connection.error instanceof ApiError ? connection.error.message : String(connection.error)
        }
        code={connection.error instanceof ApiError ? connection.error.code : undefined}
      />
    )
  }

  const profile = connection.data!

  return (
    <div className="space-y-6">
      <div>
        <Link to="/connections" className="text-sm text-brand-700 hover:underline">
          ← All connections
        </Link>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-semibold text-slate-900">{profile.name}</h1>
          <ProviderBadge provider={profile.provider} label={profile.providerLabel} />
          <span className="font-mono text-xs text-slate-500">
            {profile.bootstrapServers ??
              profile.brokerUrlOverride ??
              `${profile.host ?? ''}:${profile.port ?? ''}`}
          </span>
        </div>
      </div>

      <form onSubmit={openQueue} className="flex gap-2">
        <input
          className={`${inputClass} mt-0`}
          value={queueInput}
          onChange={(event) => setQueueInput(event.target.value)}
          placeholder={caveats.namePlaceholder}
          aria-label={`${caveats.Noun} name`}
        />
        <button type="submit" className={buttonClass}>
          Go
        </button>
      </form>

      {!hasQueue && (
        <EmptyState title={`Choose a ${caveats.noun}`} body={caveats.chooseNote} />
      )}

      {hasQueue && (
        <>
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
            <div className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-4">
                <div>
                  <span className="text-xs font-medium uppercase tracking-wide text-slate-500">
                    {caveats.depthLabel}
                  </span>
                  <div className="mt-1 text-2xl font-semibold text-slate-900">
                    {depth.isPending && '…'}
                    {depth.data && (
                      <>
                        {depth.data.count}
                        {!depth.data.exact && <span title="At least this many">+</span>}
                      </>
                    )}
                    {depth.isError && <span className="text-base text-rose-700">unavailable</span>}
                  </div>
                  {depth.data?.note && (
                    <p className="mt-1 max-w-sm text-xs text-slate-500">{depth.data.note}</p>
                  )}
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    className={secondaryButtonClass}
                    onClick={() => {
                      void depth.refetch()
                      void messages.refetch()
                    }}
                    disabled={depth.isFetching || messages.isFetching}
                  >
                    {depth.isFetching || messages.isFetching ? 'Refreshing…' : 'Refresh'}
                  </button>
                  <button
                    type="button"
                    className="rounded-lg bg-rose-600 px-3 py-2 text-sm font-medium text-white hover:bg-rose-700"
                    onClick={() => setConfirmPurge(true)}
                  >
                    Purge {caveats.noun}
                  </button>
                </div>
              </div>

              {messages.isError && (
                <ErrorBanner
                  title={`Could not read this ${caveats.noun}`}
                  message={
                    messages.error instanceof ApiError
                      ? messages.error.message
                      : String(messages.error)
                  }
                  code={messages.error instanceof ApiError ? messages.error.code : undefined}
                  onRetry={() => void messages.refetch()}
                />
              )}

              {messages.isPending && !messages.isError && <Skeleton rows={5} />}

              {messages.data && messages.data.messages.length === 0 && (
                <EmptyState
                  title={`No messages on this ${caveats.noun}`}
                  body={caveats.emptyNote}
                />
              )}

              {messages.data && messages.data.messages.length > 0 && (
                <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
                  <table className="w-full text-left text-sm">
                    <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                      <tr>
                        <th className="px-3 py-2 font-medium">Message ID</th>
                        <th className="px-3 py-2 font-medium">Enqueued</th>
                        <th className="px-3 py-2 font-medium">Body</th>
                        {caveats.canDeleteOneMessage && (
                          <th className="px-3 py-2 text-right font-medium">Actions</th>
                        )}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {messages.data.messages.map((message) => (
                        <MessageRow
                          key={message.messageId}
                          connectionId={connectionId}
                          queueName={activeQueue}
                          message={message}
                          onDelete={caveats.canDeleteOneMessage ? setPendingDelete : null}
                        />
                      ))}
                    </tbody>
                  </table>
                  <div className="border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-600">
                    Showing the first {messages.data.returned} message
                    {messages.data.returned === 1 ? '' : 's'}
                    {messages.data.truncated && ' — more may be waiting.'}
                    {messages.data.providerNote && (
                      <span className="ml-1 text-slate-500">{messages.data.providerNote}</span>
                    )}
                  </div>
                </div>
              )}
            </div>

            <SendPanel connectionId={connectionId} queueName={activeQueue} caveat={caveats} />
          </div>
        </>
      )}

      <ConfirmDialog
        open={confirmPurge}
        title="Purge every message?"
        body={
          <>
            All messages on <strong className="font-mono">{activeQueue}</strong> will be{' '}
            {caveats.canDeleteOneMessage ? 'consumed and discarded' : 'discarded'}. This cannot be
            undone.
          </>
        }
        confirmLabel={`Purge ${caveats.noun}`}
        busy={purge.isPending}
        onConfirm={runPurge}
        onCancel={() => setConfirmPurge(false)}
      />

      <ConfirmDialog
        open={pendingDelete !== null}
        title="Delete this message?"
        body={
          <>
            <span className="break-all font-mono text-xs">{pendingDelete?.messageId}</span> will be
            consumed and discarded.
            {caveats.deleteNote && (
              <span className="mt-2 block text-xs text-slate-500">{caveats.deleteNote}</span>
            )}
          </>
        }
        confirmLabel="Delete message"
        busy={deleteMessage.isPending}
        onConfirm={runDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  )
}
