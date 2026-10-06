/**
 * Hierarchical query keys, so invalidating a prefix cascades. Invalidating
 * `['connections', id, 'queue', name]` refreshes that queue's depth and message list together.
 */
export const queryKeys = {
  connections: ['connections'] as const,
  connection: (id: number) => ['connections', id] as const,
  queue: (id: number, queueName: string) => ['connections', id, 'queue', queueName] as const,
  messages: (id: number, queueName: string, limit: number, contains = '', sinceMs = 0) =>
    ['connections', id, 'queue', queueName, 'messages', limit, contains, sinceMs] as const,
  depth: (id: number, queueName: string) =>
    ['connections', id, 'queue', queueName, 'depth'] as const,
  message: (id: number, queueName: string, messageId: string) =>
    ['connections', id, 'queue', queueName, 'message', messageId] as const,
  /** Prefix over every kind/prefix listing, so creating a topic can invalidate them all at once. */
  destinationsRoot: (id: number) => ['connections', id, 'destinations'] as const,
  /** Under the connection prefix, so deleting a connection clears its listing too. */
  destinations: (id: number, kind: string, prefix: string, limit: number) =>
    ['connections', id, 'destinations', kind, prefix, limit] as const,
  savedDestinations: (id: number) => ['connections', id, 'saved-destinations'] as const,
  /**
   * Remembered send requests for one destination. Under the connection prefix so deleting a connection
   * clears them, but NOT under the queue/messages prefix: sending or purging must not refetch this list
   * except through its own mutation, which invalidates exactly this key.
   */
  savedRequests: (id: number, queueName: string) =>
    ['connections', id, 'saved-requests', queueName] as const,
  /** Prefix, so clearing the buffer invalidates every filter combination at once. */
  logsRoot: () => ['logs'] as const,
  logs: (level: string, query: string, from: string, to: string, sort: string, limit: number) =>
    ['logs', level, query, from, to, sort, limit] as const,
}
