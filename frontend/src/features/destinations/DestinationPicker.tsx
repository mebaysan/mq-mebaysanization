import { useMemo, useState } from 'react'

import { ApiError } from '../../api/client'
import { useDestinations } from '../../api/destinations'
import type { DestinationEntry, DestinationKind } from '../../api/types'
import {
  EmptyState,
  ErrorBanner,
  Skeleton,
  inputClass,
  secondaryButtonClass,
} from '../../components/Primitives'
import type { ProviderCaveat } from '../queue/ProviderCaveats'

interface DestinationPickerProps {
  open: boolean
  connectionId: number
  caveat: ProviderCaveat
  onPick: (entry: DestinationEntry) => void
  onClose: () => void
}

const KIND_STYLES: Record<DestinationKind, string> = {
  QUEUE: 'bg-sky-100 text-sky-800 ring-sky-200',
  TOPIC: 'bg-violet-100 text-violet-800 ring-violet-200',
  UNKNOWN: 'bg-slate-100 text-slate-600 ring-slate-200',
}

/**
 * Asks the broker what it has, and lets one be opened.
 *
 * <p>A modal on the queue page rather than a route of its own: the URL keeps holding `?queue=`, so
 * deep links and refresh behave exactly as they did before this existed, and picking a name is one
 * step rather than a navigation away and back.
 *
 * <p><strong>Everything below branches on `availability`, never on `destinations.length`.</strong> A
 * broker that refuses to be listed answers 200 with an empty array, and rendering that as "no queues
 * found" would be the one lie this whole feature is built to avoid.
 */
export function DestinationPicker({
  open,
  connectionId,
  caveat,
  onPick,
  onClose,
}: DestinationPickerProps) {
  const [kind, setKind] = useState<DestinationKind | null>(null)
  // Sent to the broker. IBM MQ pushes it down as `prefix*`; the others apply it server-side.
  const [prefix, setPrefix] = useState('')
  // Applied here, over whatever page came back. Deliberately a different thing, and labelled as one.
  const [search, setSearch] = useState('')
  const [showInternal, setShowInternal] = useState(false)

  const listing = useDestinations(connectionId, kind, prefix, open)
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

  if (!open) {
    return null
  }

  return (
    <div
      className="fixed inset-0 z-40 flex items-start justify-center bg-slate-900/40 p-4 sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label={caveat.listLabel}
    >
      <div className="flex max-h-full w-full max-w-2xl flex-col rounded-xl bg-white shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-slate-200 p-4">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">{caveat.listLabel}</h2>
            <p className="mt-1 text-xs text-slate-500">
              Read-only. Nothing here creates, changes or deletes anything on the broker.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-2 py-1 text-sm text-slate-500 hover:bg-slate-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="space-y-3 border-b border-slate-200 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <input
              className={`${inputClass} mt-0 w-48 flex-1`}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Filter the list below"
              aria-label="Filter loaded destinations"
            />

            {caveat.listsTopics && (
              <div className="flex items-center gap-1" role="group" aria-label="Kind">
                {([null, 'QUEUE', 'TOPIC'] as (DestinationKind | null)[]).map((option) => (
                  <button
                    key={option ?? 'ALL'}
                    type="button"
                    onClick={() => setKind(option)}
                    aria-pressed={kind === option}
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      kind === option
                        ? 'bg-brand-600 text-white'
                        : 'border border-slate-300 bg-white text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    {option === null ? 'All' : option === 'QUEUE' ? 'Queues' : 'Topics'}
                  </button>
                ))}
              </div>
            )}

            <button
              type="button"
              className={secondaryButtonClass}
              onClick={() => void listing.refetch()}
              disabled={listing.isFetching}
            >
              {listing.isFetching ? 'Reading…' : 'Refresh'}
            </button>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-xs text-slate-600">
              <input
                type="checkbox"
                checked={showInternal}
                onChange={(event) => setShowInternal(event.target.checked)}
                className="rounded border-slate-300"
              />
              Show internal
              {hiddenInternal > 0 && !showInternal && (
                <span className="text-slate-400">({hiddenInternal} hidden)</span>
              )}
            </label>

            {/* The only way past the server-side cap, so it is prominent exactly when it matters. */}
            <label
              className={`flex items-center gap-2 text-xs ${
                data?.truncated ? 'font-medium text-amber-800' : 'text-slate-600'
              }`}
            >
              Name starts with
              <input
                className={`${inputClass} mt-0 w-40 py-1 text-xs`}
                value={prefix}
                onChange={(event) => setPrefix(event.target.value)}
                placeholder="DEV."
                aria-label="Ask the broker for names starting with"
              />
            </label>
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-auto p-4">
          {listing.isError && (
            <ErrorBanner
              title="Could not reach this broker"
              message={
                listing.error instanceof ApiError
                  ? listing.error.message
                  : String(listing.error)
              }
              code={listing.error instanceof ApiError ? listing.error.code : undefined}
              onRetry={() => void listing.refetch()}
            />
          )}

          {listing.isPending && !listing.isError && <Skeleton rows={6} />}

          {/* Not an EmptyState: "the broker will not say" and "there is nothing here" are different
              answers, and this is the one place the difference is visible to a user. */}
          {data?.availability === 'UNAVAILABLE' && (
            <div className="rounded-lg border border-amber-200 bg-amber-50 p-4" role="status">
              <p className="text-sm font-semibold text-amber-900">
                This broker would not list its destinations
              </p>
              <p className="mt-1 text-sm text-amber-800">{data.note}</p>
              <p className="mt-2 text-sm text-amber-900">
                You can still type a name — everything else works normally.
              </p>
              {data.reason && <p className="mt-2 font-mono text-xs text-amber-700">{data.reason}</p>}
            </div>
          )}

          {data && data.availability !== 'UNAVAILABLE' && visible.length === 0 && (
            <EmptyState
              title="Nothing matches"
              body={
                data.destinations.length === 0
                  ? data.message
                  : 'Every destination that came back is filtered out. Clear the filter, or turn on ' +
                    'Show internal.'
              }
            />
          )}

          {visible.length > 0 && (
            <div className="overflow-hidden rounded-xl border border-slate-200">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                  <tr>
                    <th className="px-3 py-2 font-medium">Name</th>
                    <th className="px-3 py-2 font-medium">Kind</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {visible.map((entry) => (
                    <tr key={`${entry.kind}:${entry.name}`} className="hover:bg-slate-50">
                      <td className="px-3 py-1.5">
                        <button
                          type="button"
                          onClick={() => onPick(entry)}
                          className="break-all text-left font-mono text-xs text-brand-700 hover:underline"
                        >
                          {entry.name}
                        </button>
                        {entry.internal && (
                          <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-500 ring-1 ring-inset ring-slate-200">
                            internal
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-1.5">
                        <span
                          className={`inline-flex rounded px-1.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${KIND_STYLES[entry.kind]}`}
                        >
                          {entry.kind}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {data && (
          <div className="border-t border-slate-200 bg-slate-50 px-4 py-2 text-xs text-slate-600">
            {data.availability !== 'UNAVAILABLE' && (
              <span>
                Filtering {visible.length} of {data.destinations.length} loaded.{' '}
              </span>
            )}
            <span>{data.message}</span>
            {data.availability === 'PARTIAL' && (
              <span className="ml-1 text-amber-700">{data.note}</span>
            )}
            {data.availability === 'COMPLETE' && data.note && (
              <span className="ml-1 text-slate-500">{data.note}</span>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
