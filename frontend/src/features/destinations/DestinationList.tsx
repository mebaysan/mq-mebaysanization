import { useMemo, useState } from 'react'

import { ApiError } from '../../api/client'
import { useDestinations } from '../../api/destinations'
import { useSavedDestinations } from '../../api/savedDestinations'
import type { DestinationEntry, DestinationKind, SavedDestination } from '../../api/types'
import { cardClass, Skeleton } from '../../components/Primitives'
import { PlusIcon, RefreshIcon, SearchIcon, StarIcon } from '../../components/icons'
import type { ProviderCaveat } from '../queue/ProviderCaveats'
import { FavoriteStar } from './FavoriteStar'

const KIND_DOT: Record<DestinationKind, string> = {
  QUEUE: 'bg-sky-500',
  TOPIC: 'bg-violet-500',
  UNKNOWN: 'bg-slate-400',
}

// The search box is a free-text SUBSTRING filter over the loaded page (a broker-side prefix would be a
// different, narrower thing — that lives in the Advanced picker, by project rule). So the always-on rail
// asks for a big page: the more it holds, the more the substring filter can find without a round trip.
// The server clamps this to mqmanager.destinations.max-limit anyway.
const SIDEBAR_LIST_LIMIT = 2000

// How many recently-opened names to offer under the search box. Small on purpose — this is the "jump
// back to what I was just looking at" shortcut, not a second copy of the whole list.
const RECENT_SUGGESTIONS = 5

type Tab = 'all' | 'favorites'

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
 * <p>A <strong>Favorites</strong> tab shows only the destinations starred on this connection, and a
 * <strong>recent</strong> dropdown under the search box offers the last few opened — both read from the
 * same server-side remembered-destinations list, so nothing here lives in the browser.
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
  const [tab, setTab] = useState<Tab>('all')
  const [search, setSearch] = useState('')
  const [showInternal, setShowInternal] = useState(false)
  // The recent dropdown shows while the empty search box has focus. A click on a suggestion fires on
  // mousedown (before blur) so the pick is not lost to the box closing under it.
  const [searchFocused, setSearchFocused] = useState(false)

  const listing = useDestinations(connectionId, null, '', true, SIDEBAR_LIST_LIMIT)
  const data = listing.data

  const saved = useSavedDestinations(connectionId)
  const savedEntries = saved.data ?? []
  const favorites = useMemo(() => savedEntries.filter((entry) => entry.pinned), [savedEntries])
  const favoriteNames = useMemo(
    () => new Set(favorites.map((entry) => entry.name)),
    [favorites],
  )
  // Names already remembered (opened at least once): favoriting one of these is a plain pin; a name not
  // in here must be recorded first, since the pin endpoint refuses an unknown one.
  const rememberedNames = useMemo(
    () => new Set(savedEntries.map((entry) => entry.name)),
    [savedEntries],
  )
  const recents = useMemo(
    () =>
      [...savedEntries]
        .sort((a, b) => Date.parse(b.lastOpenedAt) - Date.parse(a.lastOpenedAt))
        .slice(0, RECENT_SUGGESTIONS),
    [savedEntries],
  )

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

  const showRecent = searchFocused && search.trim() === '' && recents.length > 0

  const tabClass = (selected: boolean) =>
    `inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold uppercase tracking-wide transition-colors ${
      selected ? 'bg-brand-50 text-brand-700' : 'text-fg-subtle hover:bg-hover hover:text-fg-muted'
    }`

  return (
    <aside className={`flex max-h-[calc(100dvh-9rem)] flex-col lg:sticky lg:top-24 ${cardClass}`}>
      <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-2">
        <div role="tablist" aria-label="Destinations view" className="flex items-center gap-1">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'all'}
            onClick={() => setTab('all')}
            className={tabClass(tab === 'all')}
          >
            {caveat.Noun}s
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'favorites'}
            onClick={() => setTab('favorites')}
            className={tabClass(tab === 'favorites')}
          >
            <StarIcon size={12} fill={tab === 'favorites' ? 'currentColor' : 'none'} />
            Favorites{favorites.length > 0 ? ` (${favorites.length})` : ''}
          </button>
        </div>
        {tab === 'all' && (
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
        )}
      </div>

      {tab === 'favorites' ? (
        <div className="min-h-0 flex-1 overflow-auto p-2">
          {favorites.length === 0 ? (
            <div className="px-3 py-6 text-center text-sm text-fg-subtle">
              <StarIcon size={20} className="mx-auto mb-2 text-fg-subtle" />
              No favorites yet. Open a {caveat.noun} and tap the star by its name to keep it here.
            </div>
          ) : (
            <ul className="space-y-0.5">
              {favorites.map((entry) => (
                <SavedRow
                  key={entry.name}
                  connectionId={connectionId}
                  entry={entry}
                  active={entry.name === activeName}
                  favorited
                  onOpen={onOpen}
                />
              ))}
            </ul>
          )}
        </div>
      ) : (
        <>
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
                onFocus={() => setSearchFocused(true)}
                // A short delay lets a suggestion's mousedown land before the dropdown unmounts.
                onBlur={() => window.setTimeout(() => setSearchFocused(false), 120)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault()
                    openTyped()
                  } else if (event.key === 'Escape') {
                    setSearchFocused(false)
                  }
                }}
                placeholder={caveat.namePlaceholder}
                aria-label={`Filter or open a ${caveat.noun}`}
              />

              {showRecent && (
                <div className="absolute left-0 right-0 top-full z-20 mt-1 overflow-hidden rounded-lg border border-line bg-surface shadow-lg">
                  <p className="border-b border-line px-3 py-1.5 text-[11px] font-medium uppercase tracking-wide text-fg-subtle">
                    Recent
                  </p>
                  <ul>
                    {recents.map((entry) => (
                      <li key={entry.name}>
                        <button
                          type="button"
                          // mousedown, not click: it must fire before the input's blur closes this.
                          onMouseDown={(event) => {
                            event.preventDefault()
                            onOpen({ name: entry.name, kind: entry.kind })
                            setSearchFocused(false)
                          }}
                          className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-hover"
                        >
                          {entry.pinned && (
                            <StarIcon
                              size={12}
                              fill="currentColor"
                              className="shrink-0 text-amber-500"
                            />
                          )}
                          <span className="min-w-0 flex-1 truncate font-mono text-fg-muted">
                            {entry.name}
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
            <p className="mt-1.5 px-0.5 text-[11px] text-fg-subtle">
              Filters the list by any part of the name. Press Enter to open an exact name.
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
                  : data.truncated
                    ? `Nothing in the ${data.destinations.length} loaded matches. It may be further down — ` +
                      `press Enter to open an exact name, or narrow with Advanced.`
                    : 'Nothing matches your filter.'}
              </p>
            )}

            {visible.length > 0 && (
              <ul className="space-y-0.5">
                {visible.map((entry) => (
                  <DestinationRow
                    key={`${entry.kind}:${entry.name}`}
                    connectionId={connectionId}
                    entry={entry}
                    active={entry.name === activeName}
                    favorited={favoriteNames.has(entry.name)}
                    remembered={rememberedNames.has(entry.name)}
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
              <button
                type="button"
                onClick={onBrowseAdvanced}
                className="text-brand-700 hover:underline"
              >
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
        </>
      )}
    </aside>
  )
}

/**
 * A row in the broker listing. A star sits at the end of the row: always shown once favorited, and
 * revealed on hover (or keyboard focus) otherwise, so any listed destination can be favorited in place.
 */
function DestinationRow({
  connectionId,
  entry,
  active,
  favorited,
  remembered,
  onOpen,
}: {
  connectionId: number
  entry: DestinationEntry
  active: boolean
  favorited: boolean
  remembered: boolean
  onOpen: (entry: { name: string; kind?: DestinationKind }) => void
}) {
  return (
    <li
      className={`group relative flex items-start gap-1 rounded-lg transition-[background-color] duration-150 ${
        active
          ? 'bg-gradient-to-r from-accent-50 to-brand-50 text-brand-900'
          : 'text-fg-muted hover:bg-hover'
      }`}
    >
      <button
        type="button"
        onClick={() => onOpen({ name: entry.name, kind: entry.kind })}
        aria-current={active ? 'true' : undefined}
        className="flex min-w-0 flex-1 items-start gap-2.5 py-2 pl-3 text-left transition-transform duration-150 active:scale-[0.99]"
      >
        {/* A violet spine on the open row — the accent colour earning its keep as a locator. */}
        <span
          className={`absolute left-0 top-1/2 h-6 w-1 -translate-y-1/2 rounded-full bg-gradient-to-b from-accent-500 to-brand-500 transition-opacity ${
            active ? 'opacity-100' : 'opacity-0'
          }`}
          aria-hidden="true"
        />
        <span
          className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${KIND_DOT[entry.kind]}`}
          aria-hidden="true"
        />
        {/* Wraps rather than truncates: a long, heavily-prefixed name is unreadable when its end is cut
            off, and the whole point of the list is to recognise a name. title= keeps a hover copy too. */}
        <span className="min-w-0 flex-1 break-all font-mono text-xs leading-snug" title={entry.name}>
          {entry.name}
        </span>
        {entry.internal && (
          <span className="mt-0.5 shrink-0 rounded bg-surface-2 px-1.5 py-0.5 text-[10px] text-fg-subtle ring-1 ring-inset ring-line">
            internal
          </span>
        )}
      </button>
      {/* End-of-row star: always visible once favorited, otherwise fades in on hover/focus. Favoriting a
          not-yet-remembered row records it first (see FavoriteStar), so any listed name can be starred. */}
      <div
        className={`shrink-0 pr-1 pt-1.5 transition-opacity ${
          favorited ? '' : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100'
        }`}
      >
        <FavoriteStar
          connectionId={connectionId}
          name={entry.name}
          favorited={favorited}
          remembered={remembered}
          recordKind={entry.kind}
          size={14}
        />
      </div>
    </li>
  )
}

/** A row in the Favorites tab or a remembered list: name opens it, star toggles the favorite. */
function SavedRow({
  connectionId,
  entry,
  active,
  favorited,
  onOpen,
}: {
  connectionId: number
  entry: SavedDestination
  active: boolean
  favorited: boolean
  onOpen: (entry: { name: string; kind?: DestinationKind }) => void
}) {
  return (
    <li
      className={`group relative flex items-center gap-1 rounded-lg transition-[background-color] duration-150 ${
        active
          ? 'bg-gradient-to-r from-accent-50 to-brand-50 text-brand-900'
          : 'text-fg-muted hover:bg-hover'
      }`}
    >
      <button
        type="button"
        onClick={() => onOpen({ name: entry.name, kind: entry.kind })}
        aria-current={active ? 'true' : undefined}
        className="flex min-w-0 flex-1 items-center gap-2.5 py-2 pl-3 text-left"
      >
        <span
          className={`h-1.5 w-1.5 shrink-0 rounded-full ${KIND_DOT[entry.kind]}`}
          aria-hidden="true"
        />
        <span className="min-w-0 flex-1 break-all font-mono text-xs leading-snug" title={entry.name}>
          {entry.name}
        </span>
      </button>
      <div className="pr-1">
        <FavoriteStar connectionId={connectionId} name={entry.name} favorited={favorited} size={14} />
      </div>
    </li>
  )
}
