import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import { queryKeys } from './keys'
import type { DestinationKind, SavedDestination } from './types'

/** Pinned first, then most recently opened — the order the server returns them in. */
export function useSavedDestinations(id: number) {
  return useQuery({
    queryKey: queryKeys.savedDestinations(id),
    queryFn: () => api.get<SavedDestination[]>(`/api/connections/${id}/saved-destinations`),
  })
}

/** Every mutation refreshes the one list, so the chips never disagree with the database. */
function useSavedMutation<TArgs, TResult>(id: number, mutationFn: (args: TArgs) => Promise<TResult>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.savedDestinations(id) }),
  })
}

/**
 * Records that a destination was opened. An upsert on the server, so calling it on every open is
 * safe — a name already remembered is touched rather than duplicated.
 *
 * @param kind omit when the name was typed; the server derives it from the connection's provider
 */
export function useRecordOpen(id: number) {
  return useSavedMutation(id, (body: { name: string; kind?: DestinationKind }) =>
    api.post<SavedDestination>(`/api/connections/${id}/saved-destinations`, body),
  )
}

/** Pinned destinations sort first and are never evicted by the retention cap. */
export function usePinDestination(id: number) {
  return useSavedMutation(id, (body: { name: string; pinned: boolean }) =>
    api.put<SavedDestination>(`/api/connections/${id}/saved-destinations/pin`, body),
  )
}

/** No confirmation dialog behind this: it deletes a bookmark, not a message. */
export function useForgetDestination(id: number) {
  return useSavedMutation(id, (name: string) =>
    api.delete<void>(
      `/api/connections/${id}/saved-destinations?name=${encodeURIComponent(name)}`,
    ),
  )
}
