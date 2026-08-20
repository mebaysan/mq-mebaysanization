import { ApiError } from '../../api/client'
import {
  useForgetDestination,
  usePinDestination,
  useSavedDestinations,
} from '../../api/savedDestinations'
import { useToast } from '../../components/ToastProvider'

interface RecentDestinationsProps {
  connectionId: number
  /** The destination currently open, highlighted so the row reads as a place rather than a list. */
  activeName: string
  onOpen: (name: string) => void
}

/**
 * The destinations already opened on this connection, as chips.
 *
 * <p>They come from the database rather than the browser: connections already live server-side, and
 * this tool is often run on one machine and read from another. Pinned first, then most recent.
 *
 * <p>Still useful — arguably most useful — when the broker refuses to be browsed, which is why it sits
 * outside the picker rather than inside it.
 */
export function RecentDestinations({
  connectionId,
  activeName,
  onOpen,
}: RecentDestinationsProps) {
  const toast = useToast()
  const saved = useSavedDestinations(connectionId)
  const pin = usePinDestination(connectionId)
  const forget = useForgetDestination(connectionId)

  const entries = saved.data ?? []
  if (entries.length === 0) {
    return null
  }

  const report = (err: unknown) => toast.error(err instanceof ApiError ? err.message : String(err))

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">Remembered</span>
      {entries.map((entry) => {
        const active = entry.name === activeName
        return (
          <span
            key={entry.name}
            className={`group inline-flex items-center gap-1 rounded-full border py-0.5 pl-2 pr-1 text-xs ${
              active
                ? 'border-brand-300 bg-brand-50 text-brand-800'
                : 'border-line bg-surface text-fg-muted hover:bg-hover'
            }`}
          >
            <button
              type="button"
              onClick={() => onOpen(entry.name)}
              className="max-w-[16rem] truncate font-mono"
              title={`${entry.name} — opened ${entry.openCount} time${entry.openCount === 1 ? '' : 's'}`}
            >
              {entry.pinned && <span aria-hidden="true">📌 </span>}
              {entry.name}
            </button>
            <button
              type="button"
              onClick={() =>
                pin.mutate({ name: entry.name, pinned: !entry.pinned }, { onError: report })
              }
              className="rounded px-1 text-fg-subtle hover:bg-slate-200 hover:text-fg-muted"
              title={
                entry.pinned
                  ? 'Unpin. It can then be evicted once the list is full.'
                  : 'Pin. Pinned destinations sort first and are never evicted.'
              }
              aria-label={entry.pinned ? `Unpin ${entry.name}` : `Pin ${entry.name}`}
            >
              {entry.pinned ? '−' : '+'}
            </button>
            {/* No confirmation: this deletes a bookmark, not a message. */}
            <button
              type="button"
              onClick={() => forget.mutate(entry.name, { onError: report })}
              className="rounded px-1 text-fg-subtle hover:bg-rose-100 hover:text-rose-700"
              title="Forget this destination. Nothing on the broker changes."
              aria-label={`Forget ${entry.name}`}
            >
              ✕
            </button>
          </span>
        )
      })}
    </div>
  )
}
