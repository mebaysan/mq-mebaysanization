import {
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { ApiError } from '../api/client'
import { useConnection } from '../api/connections'
import { useDeleteMessage, useDepth, useMessages, usePurgeQueue } from '../api/queue'
import { useRecordOpen } from '../api/savedDestinations'
import type { DestinationKind, QueueMessage } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import {
  cardClass,
  EmptyState,
  ErrorBanner,
  ProviderBadge,
  Skeleton,
  buttonClass,
  dangerButtonClass,
  secondaryButtonClass,
} from '../components/Primitives'
import { ArrowLeftIcon, RefreshIcon, SearchIcon } from '../components/icons'
import { useToast } from '../components/ToastProvider'
import { CreateTopicDialog } from '../features/destinations/CreateTopicDialog'
import { DestinationList } from '../features/destinations/DestinationList'
import { DestinationPicker } from '../features/destinations/DestinationPicker'
import { MessageRow } from '../features/queue/MessageRow'
import { DEFAULT_CAVEAT, PROVIDER_CAVEATS } from '../features/queue/ProviderCaveats'
import { SendPanel } from '../features/queue/SendPanel'

const BROWSE_LIMIT = 100

/** How many messages to read from the broker. Bigger values are clamped server-side. */
const LIMIT_OPTIONS = [50, 100, 200, 500]

const selectClass =
  'rounded-lg border border-line bg-surface px-2.5 py-1.5 text-sm text-fg-muted transition-colors ' +
  'hover:border-fg-subtle focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15'

/** Client-side "recent" windows, applied to the enqueue time of the messages already loaded. */
const TIME_OPTIONS: { label: string; minutes: number | null }[] = [
  { label: 'Any time', minutes: null },
  { label: 'Last 15 min', minutes: 15 },
  { label: 'Last hour', minutes: 60 },
  { label: 'Last 2 hours', minutes: 120 },
  { label: 'Last 24 hours', minutes: 1440 },
]

/** For the JMS fallback filter: id, body preview, and every header/property, lower-cased. */
function searchableText(message: QueueMessage): string {
  const kv = (entries: Record<string, string>) =>
    Object.entries(entries)
      .map(([k, v]) => `${k} ${v}`)
      .join(' ')
  return `${message.messageId} ${message.body ?? ''} ${kv(message.headers)} ${kv(message.properties)}`.toLowerCase()
}

/** A Date as the value a <input type="datetime-local"> expects (local wall clock, minute precision). */
function toLocalInput(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T` +
    `${pad(at.getHours())}:${pad(at.getMinutes())}`
  )
}

export default function QueueExplorerPage() {
  const params = useParams()
  const connectionId = Number(params.id)
  const toast = useToast()

  // The active queue lives in the URL, so a deep link or a refresh keeps working.
  const [searchParams, setSearchParams] = useSearchParams()
  const activeQueue = searchParams.get('queue') ?? ''

  const connection = useConnection(connectionId)
  const hasQueue = activeQueue.trim() !== ''

  // What to read: the count, an optional content search (committed on submit, since each search reads the
  // broker for real), and an optional start time that lets a search skip straight past old records.
  const [limit, setLimit] = useState(BROWSE_LIMIT)
  const [searchInput, setSearchInput] = useState('')
  const [committedContains, setCommittedContains] = useState('')
  const [sinceMs, setSinceMs] = useState<number | null>(null)
  const [sinceLocal, setSinceLocal] = useState('')

  const depth = useDepth(connectionId, activeQueue, hasQueue)
  const messages = useMessages(connectionId, activeQueue, limit, hasQueue, {
    contains: committedContains,
    sinceMs,
  })
  const purge = usePurgeQueue(connectionId, activeQueue)
  const deleteMessage = useDeleteMessage(connectionId, activeQueue)

  const [pendingDelete, setPendingDelete] = useState<QueueMessage | null>(null)
  const [confirmPurge, setConfirmPurge] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)

  // The rail is drag-resizable so long, heavily-prefixed names can be read in full — widen it instead of
  // relying on wrap or a hover tooltip. Width is kept in memory only (no localStorage anywhere, by
  // project rule), so it resets to the default on reload, which is fine for a per-session preference.
  const SIDEBAR_MIN = 220
  const SIDEBAR_MAX = 680
  const SIDEBAR_DEFAULT = 288
  const [sidebarWidth, setSidebarWidth] = useState(SIDEBAR_DEFAULT)

  const startSidebarResize = (event: ReactPointerEvent) => {
    event.preventDefault()
    const startX = event.clientX
    const startWidth = sidebarWidth
    const onMove = (move: PointerEvent) => {
      const next = Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, startWidth + (move.clientX - startX)))
      setSidebarWidth(next)
    }
    const onUp = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      // Restore the selection/cursor the drag borrowed from the whole document.
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
    }
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  // Never null: a blank noun would render as a missing word rather than as a loading state.
  const caveats = connection.data ? PROVIDER_CAVEATS[connection.data.provider] : DEFAULT_CAVEAT

  // Kafka searches server-side (over the full body, across the whole topic). Providers that ignore the
  // query get the same search applied client-side over the page they returned, so the box is never inert.
  const serverSearches = connection.data?.provider === 'KAFKA'
  const loadedMessages = messages.data?.messages
  const visibleMessages = useMemo(() => {
    const all = loadedMessages ?? []
    if (serverSearches) return all
    const needle = committedContains.trim().toLowerCase()
    return all.filter((message) => {
      if (needle && !searchableText(message).includes(needle)) return false
      if (sinceMs != null) {
        const at = message.enqueueTime ? new Date(message.enqueueTime).getTime() : Number.NaN
        if (Number.isNaN(at) || at < sinceMs) return false
      }
      return true
    })
  }, [loadedMessages, serverSearches, committedContains, sinceMs])

  const submitSearch = (event: React.FormEvent) => {
    event.preventDefault()
    setCommittedContains(searchInput.trim())
  }
  const applySincePreset = (minutes: number | null) => {
    if (minutes == null) {
      setSinceMs(null)
      setSinceLocal('')
      return
    }
    const at = new Date(Date.now() - minutes * 60_000)
    setSinceMs(at.getTime())
    setSinceLocal(toLocalInput(at))
  }
  const applySinceCustom = (value: string) => {
    setSinceLocal(value)
    if (value === '') {
      setSinceMs(null)
      return
    }
    const at = new Date(value).getTime()
    setSinceMs(Number.isNaN(at) ? null : at)
  }
  const clearSearch = () => {
    setSearchInput('')
    setCommittedContains('')
    applySincePreset(null)
  }
  const isSearching = committedContains !== '' || sinceMs != null

  const recordOpen = useRecordOpen(connectionId)
  // What the picker said this destination was, when it came from the picker. A typed name leaves this
  // undefined and the server derives the kind from the provider.
  const pickedKind = useRef<Map<string, DestinationKind>>(new Map())
  // Guards against StrictMode's double-effect and ordinary re-renders inflating openCount.
  const recorded = useRef<string | null>(null)

  // The URL is the single source of truth for what is open, so recording hangs off it: typing a name,
  // following a deep link and picking from the browse list all arrive here and are recorded identically.
  useEffect(() => {
    if (!hasQueue) return
    const token = `${connectionId}:${activeQueue}`
    if (recorded.current === token) return
    recorded.current = token
    recordOpen.mutate({ name: activeQueue, kind: pickedKind.current.get(activeQueue) })
    // recordOpen is a stable mutation object; depending on it would re-fire on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connectionId, activeQueue, hasQueue])

  // Every way of opening a destination funnels through here: a row click, the search box's Enter, the
  // advanced picker, and creating a topic. When the caller knows the kind (a row or the picker), it is
  // remembered so the "recently opened" record carries the real kind rather than one guessed from the
  // provider.
  const openEntry = (entry: { name: string; kind?: DestinationKind }) => {
    const trimmed = entry.name.trim()
    if (trimmed === '') return
    if (entry.kind) pickedKind.current.set(trimmed, entry.kind)
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
      <div className={`hero-surface overflow-hidden ${cardClass}`}>
        <div className="hero-grid px-5 py-5">
          <Link
            to="/connections"
            className="inline-flex items-center gap-1 text-sm font-medium text-brand-700 transition-colors hover:text-brand-800"
          >
            <ArrowLeftIcon size={15} /> All connections
          </Link>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight text-fg">{profile.name}</h1>
            <ProviderBadge provider={profile.provider} label={profile.providerLabel} />
            <span className="rounded-md bg-surface/70 px-2 py-0.5 font-mono text-xs text-fg-muted ring-1 ring-inset ring-line">
              {profile.bootstrapServers ??
                profile.brokerUrlOverride ??
                `${profile.host ?? ''}:${profile.port ?? ''}`}
            </span>
          </div>
        </div>
      </div>

      <div
        className="flex flex-col gap-5 lg:flex-row lg:gap-0 lg:items-start"
        style={{ '--rail': `${sidebarWidth}px` } as CSSProperties}
      >
        <div className="w-full lg:w-[var(--rail)] lg:shrink-0">
          <DestinationList
            connectionId={connectionId}
            caveat={caveats}
            activeName={activeQueue}
            onOpen={openEntry}
            onBrowseAdvanced={() => setPickerOpen(true)}
            onCreate={caveats.canCreateTopic ? () => setCreateOpen(true) : undefined}
          />
        </div>

        {/* Drag to widen the rail so long names are readable; double-click resets, arrows nudge it.
            Desktop only — on a stacked mobile layout there is no column to resize. */}
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize the list. Drag, or use the left and right arrow keys."
          tabIndex={0}
          onPointerDown={startSidebarResize}
          onDoubleClick={() => setSidebarWidth(SIDEBAR_DEFAULT)}
          onKeyDown={(event) => {
            if (event.key === 'ArrowLeft') {
              event.preventDefault()
              setSidebarWidth((w) => Math.max(SIDEBAR_MIN, w - 16))
            } else if (event.key === 'ArrowRight') {
              event.preventDefault()
              setSidebarWidth((w) => Math.min(SIDEBAR_MAX, w + 16))
            }
          }}
          title="Drag to resize · double-click to reset"
          className="group hidden w-6 shrink-0 cursor-col-resize touch-none select-none items-center justify-center rounded focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand-500/20 lg:flex lg:sticky lg:top-24 lg:h-[calc(100dvh-9rem)]"
        >
          {/* A slim pill inside a 24px grab zone — the whole 24px is draggable, the pill just marks it and
              thickens on hover so the target is findable. */}
          <span className="h-16 w-1 rounded-full bg-line transition-all group-hover:h-20 group-hover:w-1.5 group-hover:bg-brand-400 group-focus-visible:bg-brand-400" />
        </div>

        <div className="min-w-0 flex-1">
          {!hasQueue && (
            <div className="flex h-full items-center">
              <EmptyState title={`Select a ${caveats.noun}`} body={caveats.chooseNote} />
            </div>
          )}

          {hasQueue && (
            <div key={activeQueue} className="animate-fade-in min-w-0 space-y-4">
                <div className={`flex flex-wrap items-start justify-between gap-3 p-4 ${cardClass}`}>
                  <div className="min-w-0">
                    <h2 className="truncate font-mono text-sm font-semibold text-fg">
                      {activeQueue}
                    </h2>
                    <div className="mt-1.5 flex items-baseline gap-2">
                      <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">
                        {caveats.depthLabel}
                      </span>
                      <span className="text-2xl font-semibold tabular-nums text-fg">
                        {depth.isPending && '…'}
                        {depth.data && (
                          <>
                            {depth.data.count}
                            {!depth.data.exact && <span title="At least this many">+</span>}
                          </>
                        )}
                        {depth.isError && (
                          <span className="text-base text-rose-700">unavailable</span>
                        )}
                      </span>
                    </div>
                    {depth.data?.note && (
                      <p className="mt-1.5 max-w-sm text-xs leading-relaxed text-fg-subtle">
                        {depth.data.note}
                      </p>
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
                      <RefreshIcon
                        size={14}
                        className={depth.isFetching || messages.isFetching ? 'animate-spin' : undefined}
                      />
                      {depth.isFetching || messages.isFetching ? 'Refreshing…' : 'Refresh'}
                    </button>
                    <button
                      type="button"
                      className={dangerButtonClass}
                      onClick={() => setConfirmPurge(true)}
                    >
                      Purge {caveats.noun}
                    </button>
                  </div>
                </div>

                <SendPanel connectionId={connectionId} queueName={activeQueue} caveat={caveats} />

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

                {messages.data && (
                  <div className="space-y-3">
                    <div className={`space-y-2 p-2.5 ${cardClass}`}>
                      <form onSubmit={submitSearch} className="flex flex-wrap items-center gap-2">
                        <div className="relative min-w-[12rem] flex-1">
                          <SearchIcon
                            size={15}
                            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-fg-subtle"
                          />
                          <input
                            className="w-full rounded-lg border border-line bg-surface py-1.5 pl-8 pr-3 text-sm text-fg placeholder:text-fg-subtle transition-[box-shadow,border-color] hover:border-fg-subtle focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15"
                            value={searchInput}
                            onChange={(event) => setSearchInput(event.target.value)}
                            placeholder={`Search ${caveats.noun} content…`}
                            aria-label="Search message content"
                          />
                        </div>
                        <button type="submit" className={buttonClass} disabled={messages.isFetching}>
                          {messages.isFetching ? 'Searching…' : 'Search'}
                        </button>
                        <select
                          className={selectClass}
                          value={limit}
                          onChange={(event) => setLimit(Number(event.target.value))}
                          aria-label="How many to load"
                        >
                          {LIMIT_OPTIONS.map((n) => (
                            <option key={n} value={n}>
                              Load {n}
                            </option>
                          ))}
                        </select>
                        {(isSearching || searchInput !== '') && (
                          <button type="button" className={secondaryButtonClass} onClick={clearSearch}>
                            Clear
                          </button>
                        )}
                      </form>
                      <div className="flex flex-wrap items-center gap-1.5 text-xs text-fg-muted">
                        <span className="mr-0.5 font-medium">Since</span>
                        {TIME_OPTIONS.map((option) => {
                          const active = option.minutes == null && sinceMs == null
                          return (
                            <button
                              key={option.label}
                              type="button"
                              onClick={() => applySincePreset(option.minutes)}
                              aria-pressed={active}
                              className={`rounded-lg border px-2 py-1 font-medium transition-colors ${
                                active
                                  ? 'border-brand-200 bg-brand-50 text-brand-700'
                                  : 'border-line bg-surface text-fg-muted hover:bg-hover'
                              }`}
                            >
                              {option.label}
                            </button>
                          )
                        })}
                        <input
                          type="datetime-local"
                          step={60}
                          value={sinceLocal}
                          onChange={(event) => applySinceCustom(event.target.value)}
                          className={`${selectClass} py-1`}
                          aria-label="Custom start time"
                        />
                      </div>
                    </div>

                    {visibleMessages.length > 0 ? (
                      <div className={`overflow-hidden ${cardClass}`}>
                        <table className="w-full text-left text-sm">
                          <thead className="border-b border-line bg-surface-2/70 text-xs uppercase tracking-wide text-fg-subtle">
                            <tr>
                              <th className="px-3 py-2 font-medium">Message ID</th>
                              <th className="px-3 py-2 font-medium">Enqueued</th>
                              <th className="px-3 py-2 font-medium">Body</th>
                              {caveats.canDeleteOneMessage && (
                                <th className="px-3 py-2 text-right font-medium">Actions</th>
                              )}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-line">
                            {visibleMessages.map((message) => (
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
                        <div className="border-t border-line bg-surface-2/70 px-3 py-2 text-xs text-fg-muted">
                          {isSearching ? 'Found ' : 'Showing '}
                          {visibleMessages.length} message{visibleMessages.length === 1 ? '' : 's'}
                          {!isSearching &&
                            messages.data.truncated &&
                            ' — more may be waiting; raise the load count.'}
                          {messages.data.providerNote && (
                            <span className="ml-1 text-fg-subtle">{messages.data.providerNote}</span>
                          )}
                        </div>
                      </div>
                    ) : (
                      <EmptyState
                        title={isSearching ? 'No matches' : `No messages on this ${caveats.noun}`}
                        body={
                          isSearching
                            ? (messages.data.providerNote ??
                              'Nothing matched. Widen the time window, raise the load count, or change the text.')
                            : caveats.emptyNote
                        }
                      />
                    )}
                  </div>
                )}
            </div>
          )}
        </div>
      </div>

      <DestinationPicker
        open={pickerOpen}
        connectionId={connectionId}
        caveat={caveats}
        onPick={(entry) => {
          openEntry({ name: entry.name, kind: entry.kind })
          setPickerOpen(false)
        }}
        onClose={() => setPickerOpen(false)}
      />

      <CreateTopicDialog
        open={createOpen}
        connectionId={connectionId}
        onClose={() => setCreateOpen(false)}
        onCreated={(name) => {
          // Open the topic straight after creating it, the same path the picker and a typed name take.
          openEntry({ name, kind: 'TOPIC' })
        }}
      />

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
              <span className="mt-2 block text-xs text-fg-subtle">{caveats.deleteNote}</span>
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
