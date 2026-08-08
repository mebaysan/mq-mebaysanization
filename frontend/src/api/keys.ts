/**
 * Hierarchical query keys, so invalidating a prefix cascades. Invalidating
 * `['connections', id, 'queue', name]` refreshes that queue's depth and message list together.
 */
export const queryKeys = {
  connections: ['connections'] as const,
  connection: (id: number) => ['connections', id] as const,
  queue: (id: number, queueName: string) => ['connections', id, 'queue', queueName] as const,
  messages: (id: number, queueName: string, limit: number) =>
    ['connections', id, 'queue', queueName, 'messages', limit] as const,
  depth: (id: number, queueName: string) =>
    ['connections', id, 'queue', queueName, 'depth'] as const,
  message: (id: number, queueName: string, messageId: string) =>
    ['connections', id, 'queue', queueName, 'message', messageId] as const,
}
