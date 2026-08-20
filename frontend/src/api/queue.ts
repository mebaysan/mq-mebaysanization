import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api, queueQuery } from './client'
import { queryKeys } from './keys'
import type {
  BrowseResult,
  DeleteMessageResult,
  DepthResult,
  MessageType,
  PurgeResult,
  QueueMessage,
  SendMessageResult,
  TargetClient,
} from './types'

export function useDepth(id: number, queueName: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.depth(id, queueName),
    queryFn: () => api.get<DepthResult>(`/api/connections/${id}/queue/depth?${queueQuery(queueName)}`),
    enabled,
  })
}

export function useMessages(
  id: number,
  queueName: string,
  limit: number,
  enabled: boolean,
  search?: { contains?: string; sinceMs?: number | null },
) {
  const contains = search?.contains?.trim() ?? ''
  const sinceMs = search?.sinceMs ?? null
  const extra: Record<string, string | number> = { limit }
  if (contains !== '') extra.contains = contains
  if (sinceMs != null) extra.since = sinceMs
  return useQuery({
    queryKey: queryKeys.messages(id, queueName, limit, contains, sinceMs ?? 0),
    queryFn: () =>
      api.get<BrowseResult>(`/api/connections/${id}/queue/messages?${queueQuery(queueName, extra)}`),
    enabled,
    // A content search reads the broker for real (up to seconds); do not re-run it on window focus etc.
    staleTime: 30_000,
  })
}

/** Fetches the untruncated body for an expanded row. Non-destructive. */
export function useMessageDetail(id: number, queueName: string, messageId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.message(id, queueName, messageId),
    queryFn: () =>
      api.get<QueueMessage>(
        `/api/connections/${id}/queue/messages/one?${queueQuery(queueName, { messageId })}`,
      ),
    enabled,
    retry: false,
  })
}

/** Every mutation invalidates the whole queue key, so depth and the message list refresh together. */
function useQueueMutation<TArgs, TResult>(
  id: number,
  queueName: string,
  mutationFn: (args: TArgs) => Promise<TResult>,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.queue(id, queueName) }),
  })
}

export function useSendMessage(id: number, queueName: string) {
  return useQueueMutation(
    id,
    queueName,
    (body: {
      payload: string
      properties: Record<string, string>
      key: string | null
      messageType: MessageType | null
      targetClient: TargetClient | null
    }) =>
      api.post<SendMessageResult>(
        `/api/connections/${id}/queue/messages?${queueQuery(queueName)}`,
        body,
      ),
  )
}

export function useDeleteMessage(id: number, queueName: string) {
  return useQueueMutation(id, queueName, (messageId: string) =>
    api.delete<DeleteMessageResult>(
      `/api/connections/${id}/queue/messages?${queueQuery(queueName, { messageId })}`,
    ),
  )
}

export function usePurgeQueue(id: number, queueName: string) {
  return useQueueMutation(id, queueName, () =>
    api.delete<PurgeResult>(`/api/connections/${id}/queue/purge?${queueQuery(queueName)}`),
  )
}
