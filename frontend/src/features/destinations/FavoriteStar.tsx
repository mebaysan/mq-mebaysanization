import { ApiError } from '../../api/client'
import { usePinDestination, useRecordOpen } from '../../api/savedDestinations'
import type { DestinationKind } from '../../api/types'
import { StarIcon } from '../../components/icons'
import { useToast } from '../../components/ToastProvider'

/**
 * A star that adds or removes a destination from this connection's favorites.
 *
 * <p>"Favorite" is the same server-side flag as a pinned remembered destination — pinned rows sort
 * first and are never evicted — surfaced here as a star rather than a separate concept, so there is one
 * list and no way for two to disagree.
 *
 * <p>The pin endpoint refuses a name that was never opened, so before favoriting one that is not yet
 * remembered (a plain row in the broker list) the destination is recorded first. Pass {@code recordKind}
 * from such a list; the header star omits it, since opening a destination already recorded it.
 */
export function FavoriteStar({
  connectionId,
  name,
  favorited,
  recordKind,
  remembered = true,
  size = 16,
}: {
  connectionId: number
  name: string
  favorited: boolean
  /** The kind to record with when favoriting a not-yet-remembered destination from a list. */
  recordKind?: DestinationKind
  /** False when this destination may not be remembered yet, so favoriting must record it first. */
  remembered?: boolean
  size?: number
}) {
  const toast = useToast()
  const pin = usePinDestination(connectionId)
  const recordOpen = useRecordOpen(connectionId)

  const report = (err: unknown) => toast.error(err instanceof ApiError ? err.message : String(err))
  const busy = pin.isPending || recordOpen.isPending

  const toggle = async (event: React.MouseEvent) => {
    // Stops a star inside a clickable row from also opening that row.
    event.stopPropagation()
    try {
      // Favoriting a destination the server has never seen would 404 on pin, so record it first.
      if (!favorited && !remembered) {
        await recordOpen.mutateAsync({ name, kind: recordKind })
      }
      await pin.mutateAsync({ name, pinned: !favorited })
    } catch (err) {
      report(err)
    }
  }

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={busy}
      aria-pressed={favorited}
      title={favorited ? 'Remove from favorites' : 'Add to favorites'}
      aria-label={favorited ? `Remove ${name} from favorites` : `Add ${name} to favorites`}
      className={`shrink-0 rounded-md p-1 transition-colors disabled:opacity-50 ${
        favorited
          ? 'text-amber-500 hover:bg-amber-50 dark:hover:bg-amber-950/40'
          : 'text-fg-subtle hover:bg-hover hover:text-amber-500'
      }`}
    >
      <StarIcon size={size} fill={favorited ? 'currentColor' : 'none'} />
    </button>
  )
}
