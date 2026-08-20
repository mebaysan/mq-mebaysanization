import { useMemo, useState } from 'react'

import { ApiError } from '../../api/client'
import { useDestinations } from '../../api/destinations'
import type { DestinationEntry, DestinationKind } from '../../api/types'
import { cardClass, Skeleton } from '../../components/Primitives'
import { PlusIcon, RefreshIcon, SearchIcon } from '../../components/icons'
import type { ProviderCaveat } from '../queue/ProviderCaveats'

const KIND_DOT: Record<DestinationKind, string> = {
  QUEUE: 'bg-sky-500',
  TOPIC: 'bg-violet-500',
  UNKNOWN: 'bg-slate-400',
}

interface DestinationListProps {
  connectionId: number
  caveat: ProviderCaveat
  /** The currently open name, highlighted in the list. Empty when nothing is open yet. */
  activeName: string
  onOpen: (entry: { name: string; kind?: DestinationKind }) => void
  /** Opens the server-side prefix browser, for brokers with more destinations than one page holds. */
  onBrowseAdvanced: () => void
  /** Kafka only: opens the create-topic dialog. */
  onCreate?: () => void
}

/**
 * The always-visible list of a broker's destinations, down the side of the queue page.
 *
 * <p>This is the primary way to move around now: the names sit stacked and the <em>whole row</em> opens
 * one, so there is no hunting for a small link. The search box does double duty — it filters the loaded
 * list as you type, and Enter opens whatever is typed verbatim, which is the only way in on IBM MQ (you
 * must name an existing queue) and on any broker that will not list itself.
 *
 * <p>Everything still branches on {@code availability}, never on {@code destinations.length}: a broker
 * that refuses to be listed says so here, rather than looking like an empty broker.
 */
export function DestinationList({
  connectionId,
  caveat,
  activeName,
  onOpen,
  onBrowseAdvanced,
  onCreate,
}: DestinationListProps) {
  const [search, setSearch] = useState('')
  const [showInternal, setShowInternal] = useState(false)

  const listing = useDestinations(connectionId, null, '', true)
  const data = listing.data

  const visible = useMemo(() => {
    const all = data?.destinations ?? []
    const needle = search.trim().toLowerCase()
    return all.filter(
      (entry) =>
        (showInternal || !entry.internal) &&
        (needle === '' || entry.name.toLowerCase().includes(needle)),
    )
  }, [data, search, showInternal])

  const hiddenInternal = useMemo(
    () => (data?.destinations ?? []).filter((entry) => entry.internal).length,
    [data],
  )

  const openTyped = () => {
    const trimmed = search.trim()
    if (trimmed !== '') onOpen({ name: trimmed })
  }

  return (
    <aside className={`flex max-h-[calc(100dvh-9rem)] flex-col lg:sticky lg:top-24 ${cardClass}`}>
      <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-2.5">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">
          {caveat.Noun}s
        </h2>
        <button
          type="button"
          onClick={() => void listing.refetch()}
          disabled={listing.isFetching}
          className="rounded-md p-1.5 text-fg-subtle transition-colors hover:bg-hover hover:text-fg-muted disabled:opacity-50"
          aria-label="Refresh the list"
          title="Refresh"
        >
          <RefreshIcon size={15} className={listing.isFetching ? 'animate-spin' : undefined} />
        </button>
      </div>

      <div className="border-b border-line p-3">
        <div className="relative">
          <SearchIcon
            size={15}
            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-fg-subtle"
          />
          <input
            className="w-full rounded-lg border border-line bg-surface py-2 pl-8 pr-3 text-sm shadow-xs transition-[box-shadow,border-color] duration-150 placeholder:text-fg-subtle hover:border-fg-subtle focus:border-brand-500 focus:outline-none focus:ring-4 focus:ring-brand-500/15"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                openTyped()
              }
            }}
            placeholder={caveat.namePlaceholder}
            aria-label={`Filter or open a ${caveat.noun}`}
          />
        </div>
        <p className="mt-1.5 px-0.5 text-[11px] text-fg-subtle">
          Press Enter to open the exact name you typed.
        </p>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-2">
        {listing.isPending && !listing.isError && (
          <div className="p-1">
            <Skeleton rows={6} />
          </div>
        )}

        {listing.isError && (
          <div className="m-1 rounded-lg border border-rose-200 dark:border-rose-900/60 bg-rose-50 dark:bg-rose-950/40 p-3 text-xs text-rose-800 dark:text-rose-200">
            <p className="font-medium">Could not reach this broker.</p>
            <p className="mt-1">
              {listing.error instanceof ApiError ? listing.error.message : String(listing.error)}
            </p>
            <button
              type="button"
              onClick={() => void listing.refetch()}
              className="mt-2 rounded-md border border-rose-300 bg-surface px-2 py-1 font-medium text-rose-800 dark:text-rose-200 hover:bg-rose-100"
            >
              Retry
            </button>
          </div>
        )}

        {data?.availability === 'UNAVAILABLE' && (
          <div className="m-1 rounded-lg border border-amber-200 dark:border-amber-900/60 bg-amber-50 dark:bg-amber-950/40 p-3 text-xs text-amber-900 dark:text-amber-200">
            <p className="font-medium">This broker would not list its {caveat.noun}s.</p>
            <p className="mt-1">{data.note ?? 'Type a name above and press Enter to open it.'}</p>
          </div>
        )}

        {data && data.availability !== 'UNAVAILABLE' && visible.length === 0 && (
          <p className="px-3 py-6 text-center text-sm text-fg-subtle">
            {data.destinations.length === 0
              ? `No ${caveat.noun}s here yet.`
              : 'Nothing matches your filter.'}
          </p>
        )}

        {visible.length > 0 && (
          <ul className="space-y-0.5">
            {visible.map((entry) => (
              <DestinationRow
                key={`${entry.kind}:${entry.name}`}
                entry={entry}
                active={entry.name === activeName}
                onOpen={onOpen}
              />
            ))}
          </ul>
        )}
      </div>

      <div className="flex items-center justify-between gap-2 border-t border-line px-3 py-2.5 text-[11px] text-fg-subtle">
        <div className="flex items-center gap-2">
          <label className="flex cursor-pointer items-center gap-1.5">
            <input
              type="checkbox"
              checked={showInternal}
              onChange={(event) => setShowInternal(event.target.checked)}
              className="rounded border-line"
            />
            Internal
            {hiddenInternal > 0 && !showInternal && (
              <span className="text-fg-subtle">({hiddenInternal})</span>
            )}
          </label>
          <button type="button" onClick={onBrowseAdvanced} className="text-brand-700 hover:underline">
            Advanced…
          </button>
        </div>
        {onCreate && (
          <button
            type="button"
            onClick={onCreate}
            className="inline-flex items-center gap-1 rounded-md bg-brand-50 px-2 py-1 font-medium text-brand-700 transition-colors hover:bg-brand-100"
          >
            <PlusIcon size={13} />
            New {caveat.noun}
          </button>
        )}
      </div>
    </aside>
  )
}

function DestinationRow({
  entry,
  active,
  onOpen,
}: {
  entry: DestinationEntry
  active: boolean
  onOpen: (entry: { name: string; kind?: DestinationKind }) => void
}) {
  return (
    <li>
      <button
        type="button"
        onClick={() => onOpen({ name: entry.name, kind: entry.kind })}
        aria-current={active ? 'true' : undefined}
        className={`group relative flex w-full items-center gap-2.5 rounded-lg py-2 pl-3 pr-2 text-left transition-colors duration-150 ${
          active
            ? 'bg-gradient-to-r from-accent-50 to-brand-50 text-brand-900'
            : 'text-fg-muted hover:bg-hover'
        }`}
      >
        {/* A violet spine on the open row — the accent colour earning its keep as a locator. */}
        <span
          className={`absolute left-0 top-1/2 h-6 w-1 -translate-y-1/2 rounded-full bg-gradient-to-b from-accent-500 to-brand-500 transition-opacity ${
            active ? 'opacity-100' : 'opacity-0'
          }`}
          aria-hidden="true"
        />
        <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${KIND_DOT[entry.kind]}`} aria-hidden="true" />
        <span className="min-w-0 flex-1 truncate font-mono text-xs">{entry.name}</span>
        {entry.internal && (
          <span className="shrink-0 rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-fg-subtle ring-1 ring-inset ring-line">
            internal
          </span>
        )}
      </button>
    </li>
  )
}
