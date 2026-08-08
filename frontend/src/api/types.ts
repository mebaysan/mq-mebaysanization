export type Provider = 'ACTIVE_MQ' | 'ARTEMIS' | 'IBM_MQ' | 'KAFKA'

export interface ConnectionProfile {
  id: number
  name: string
  provider: Provider
  providerLabel: string
  host: string | null
  port: number | null
  username: string | null
  /** Whether a password is stored. The value itself is never sent to the client. */
  hasPassword: boolean
  /** False when the stored password can no longer be decrypted with the current key. */
  credentialsReadable: boolean
  brokerUrlOverride: string | null
  /** Kafka only: the comma-separated seed-broker list that replaces host and port. */
  bootstrapServers: string | null
  queueManagerName: string | null
  channel: string | null
  createdAt: string
  updatedAt: string
}

/** `password: null` on update means "keep the stored one"; `''` means "clear it". */
export interface ConnectionProfileRequest {
  id?: number | null
  name: string
  provider: Provider
  host?: string | null
  port?: number | null
  username?: string | null
  password?: string | null
  brokerUrlOverride?: string | null
  bootstrapServers?: string | null
  queueManagerName?: string | null
  channel?: string | null
}

export interface ConnectionTestResult {
  success: boolean
  code: string
  message: string
  durationMs: number
}

export interface QueueMessage {
  messageId: string
  correlationId: string | null
  enqueueTime: string | null
  priority: number | null
  redelivered: boolean | null
  type: string | null
  body: string | null
  bodyTruncated: boolean
  headers: Record<string, string>
  properties: Record<string, string>
  note: string | null
}

export interface BrowseResult {
  messages: QueueMessage[]
  returned: number
  limit: number
  truncated: boolean
  providerNote: string | null
}

export interface DepthResult {
  count: number
  /** When false the UI must render `count` as "N+", never as an exact total. */
  exact: boolean
  note: string | null
}

export interface PurgeResult {
  purged: number
  stopReason: 'QUEUE_EMPTY' | 'MESSAGE_CAP' | 'TIME_CAP'
  complete: boolean
  message: string
  /** Provider-specific caveat. Kafka uses it to say a purge advances the log start offset. */
  note: string | null
}

export interface DeleteMessageResult {
  deleted: boolean
  message: string
}

export interface SendMessageResult {
  messageId: string
}

export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

export interface LogEntry {
  /** Monotonic per-process counter. Stable identity for a row, even as the buffer rolls. */
  sequence: number
  timestamp: string
  level: LogLevel
  logger: string
  thread: string
  message: string
  /** Present only when the line carried an exception. */
  stackTrace: string | null
}

/** A read of the in-memory ring buffer behind the monitoring page. */
export interface LogSnapshot {
  entries: LogEntry[]
  /** Lines currently in the buffer, matched or not. */
  held: number
  capacity: number
  /** Lines evicted since startup. Non-zero means the history shown is incomplete. */
  dropped: number
}

/** The single error shape every endpoint uses. */
export interface ApiErrorBody {
  status: number
  error: string
  message: string
  code?: string
  path?: string
  timestamp?: string
}
