import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import { queryKeys } from './keys'
import type { LogLevel, LogSnapshot, LogSort } from './types'

/**
 * Everything that narrows a read of the buffer.
 *
 * <p>An object rather than six positional arguments, for the same reason the backend's capability set
 * is not a constructor full of booleans: at this width a positional list is unreadable and two
 * transposed strings are a silent behaviour change.
 */
export interface LogFilters {
  level: LogLevel
  /** Case-insensitive substring over the message and the logger name. */
  query: string
  /** ISO-8601 instant, or '' for no bound. Never a local wall-clock string — the server would have to guess a zone. */
  from: string
  to: string
  /** Presentation order. A limit always returns the newest matches, whichever way this points. */
  sort: LogSort
  limit: number
}

/**
 * Reads the application's own recent log lines.
 *
 * <p>Polls rather than streams. The buffer is small and bounded, so re-reading the visible window is
 * cheaper in moving parts than a server-sent-event channel would be — and a poll that fails simply
 * shows stale data instead of leaving a dead socket that looks alive.
 *
 * @param refetchMs how often to re-read, or false to hold the current view still
 */
export function useLogs(filters: LogFilters, refetchMs: number | false) {
  const query = filters.query.trim()
  const params = new URLSearchParams({
    level: filters.level,
    sort: filters.sort,
    limit: String(filters.limit),
  })
  if (query !== '') {
    params.set('q', query)
  }
  if (filters.from !== '') {
    params.set('from', filters.from)
  }
  if (filters.to !== '') {
    params.set('to', filters.to)
  }
  return useQuery({
    queryKey: queryKeys.logs(filters.level, query, filters.from, filters.to, filters.sort, filters.limit),
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
