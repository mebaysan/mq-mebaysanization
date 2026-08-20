import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import { queryKeys } from './keys'
import type {
  CreateTopicRequest,
  CreateTopicResult,
  DestinationKind,
  DestinationListing,
} from './types'

/**
 * Asks a broker what destinations it has.
 *
 * <p>Fetched on demand and never polled: an advisory settle window or a PCF round trip is not free,
 * and a picker that quietly re-queries while open would cost more than it is worth. There is also no
 * server-side cache — a stale list is worse than a slow one for an admin tool, and there is no
 * invalidation signal this tool could observe. React Query's own staleness plus an explicit Refresh is
 * the honest place for it.
 *
 * @param prefix a NAME PREFIX handed to the broker, not a substring. Substring search happens in the
 *               picker, over whatever page came back, and says so
 */
export function useDestinations(
  id: number,
  kind: DestinationKind | null,
  prefix: string,
  enabled: boolean,
) {
  const trimmed = prefix.trim()
  const params = new URLSearchParams()
  if (kind) {
    params.set('kind', kind)
  }
  if (trimmed !== '') {
    params.set('prefix', trimmed)
  }
  const query = params.toString()

  return useQuery({
    queryKey: queryKeys.destinations(id, kind ?? '', trimmed),
    queryFn: () =>
      api.get<DestinationListing>(
        `/api/connections/${id}/destinations${query === '' ? '' : `?${query}`}`,
      ),
    enabled,
    // One listing is one broker round trip. Re-opening the picker within the minute should not repeat it.
    staleTime: 60_000,
  })
}

/**
 * Creates a topic, then invalidates every cached listing for this connection so the new one shows up on
 * the next browse without a manual refresh.
 */
export function useCreateTopic(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateTopicRequest) =>
      api.post<CreateTopicResult>(`/api/connections/${id}/destinations`, body),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.destinationsRoot(id) }),
  })
}
