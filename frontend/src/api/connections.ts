import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { api } from './client'
import { queryKeys } from './keys'
import type { ConnectionProfile, ConnectionProfileRequest, ConnectionTestResult } from './types'

export function useConnections() {
  return useQuery({
    queryKey: queryKeys.connections,
    queryFn: () => api.get<ConnectionProfile[]>('/api/connections'),
  })
}

export function useConnection(id: number | undefined) {
  return useQuery({
    queryKey: queryKeys.connection(id ?? -1),
    queryFn: () => api.get<ConnectionProfile>(`/api/connections/${id}`),
    enabled: id !== undefined,
  })
}

export function useSaveConnection(id?: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ConnectionProfileRequest) =>
      id === undefined
        ? api.post<ConnectionProfile>('/api/connections', request)
        : api.put<ConnectionProfile>(`/api/connections/${id}`, request),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.connections }),
  })
}

export function useDeleteConnection() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/api/connections/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.connections }),
  })
}

/** Tests a saved profile using its stored password. */
export function useTestConnection() {
  return useMutation({
    mutationFn: (id: number) => api.post<ConnectionTestResult>(`/api/connections/${id}/test`),
  })
}

/**
 * Tests an unsaved draft. Passing the profile's `id` lets the server fall back to the stored password
 * when the user has not retyped it, which is the normal case on an edit form.
 */
export function useTestDraft() {
  return useMutation({
    mutationFn: (request: ConnectionProfileRequest) =>
      api.post<ConnectionTestResult>('/api/connections/test', request),
  })
}
