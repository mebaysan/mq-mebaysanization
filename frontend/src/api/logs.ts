import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import { queryKeys } from './keys'
import type { LogLevel, LogSnapshot } from './types'

/**
 * Reads the application's own recent log lines.
 *
 * <p>Polls rather than streams. The buffer is small and bounded, so re-reading the visible window is
 * cheaper in moving parts than a server-sent-event channel would be — and a poll that fails simply
 * shows stale data instead of leaving a dead socket that looks alive.
 *
 * @param refetchMs how often to re-read, or false to hold the current view still
 */
export function useLogs(
  level: LogLevel,
  query: string,
  limit: number,
  refetchMs: number | false,
) {
  const params = new URLSearchParams({ level, limit: String(limit) })
  if (query.trim() !== '') {
    params.set('q', query.trim())
  }
  return useQuery({
    queryKey: queryKeys.logs(level, query.trim(), limit),
    queryFn: () => api.get<LogSnapshot>(`/api/logs?${params.toString()}`),
    refetchInterval: refetchMs,
    // A tail should not blank out and re-skeleton on every poll or filter change.
    placeholderData: (previous) => previous,
  })
}

/** Empties the in-memory buffer. Does not touch stdout, so nothing shipping logs off the box loses any. */
export function useClearLogs() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete<void>('/api/logs'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.logsRoot() }),
  })
}
