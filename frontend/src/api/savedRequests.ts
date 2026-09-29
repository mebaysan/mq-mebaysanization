import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, queueQuery } from './client'
import { queryKeys } from './keys'
import type { MessageType, SavedRequest, TargetClient } from './types'

/** Everything remembered for one destination, newest first — named saves and history together. */
export function useSavedRequests(id: number, queueName: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.savedRequests(id, queueName),
    queryFn: () =>
      api.get<SavedRequest[]>(`/api/connections/${id}/queue/saved-requests?${queueQuery(queueName)}`),
    enabled,
  })
}

/** Every mutation refreshes the one list, so the panel never disagrees with the database. */
function useRequestMutation<TArgs, TResult>(
  id: number,
  queueName: string,
  mutationFn: (args: TArgs) => Promise<TResult>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.savedRequests(id, queueName) }),
  })
}

/** The composed request, minus the label: a getter passes the label per call. */
export interface RequestPayload {
  payload: string
  properties: Record<string, string>
  key: string | null
  messageType: MessageType | null
  targetClient: TargetClient | null
}

/**
 * Remembers a request. Omit `label` (or pass null) to append an automatic history entry, trimmed to a
 * cap on the server; pass a label to save a named request that is never evicted. Safe to call on every
 * successful send.
 */
export function useSaveRequest(id: number, queueName: string) {
  return useRequestMutation(id, queueName, (body: RequestPayload & { label?: string | null }) =>
    api.post<SavedRequest>(
      `/api/connections/${id}/queue/saved-requests?${queueQuery(queueName)}`,
      body,
    ),
  )
}

/** No confirmation behind this: it deletes a bookmark, not a message. */
export function useForgetRequest(id: number, queueName: string) {
  return useRequestMutation(id, queueName, (requestId: number) =>
    api.delete<void>(
      `/api/connections/${id}/queue/saved-requests?${queueQuery(queueName, { requestId })}`,
    ),
  )
}
