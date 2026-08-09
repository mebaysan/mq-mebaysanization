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

export type DestinationKind = 'QUEUE' | 'TOPIC' | 'UNKNOWN'

/**
 * How much of a destination listing to trust.
 *
 * An empty `destinations` array means "there is genuinely nothing here" ONLY when this is `COMPLETE`.
 * `UNAVAILABLE` always carries an empty array, so branch on this before you look at the array.
 */
export type DestinationAvailability = 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE'

export interface DestinationEntry {
  name: string
  kind: DestinationKind
  /** A name the broker owns rather than the user: SYSTEM.*, __consumer_offsets, and so on. */
  internal: boolean
}

export interface DestinationListing {
  destinations: DestinationEntry[]
  returned: number
  limit: number
  /** True when OUR cap cut the list, never when the broker stopped short. */
  truncated: boolean
  availability: DestinationAvailability
  /** A stable `DESTINATION_LIST_*` code. A body code on a 200, not an ApiError code. */
  reason: string | null
  /** Plain-language name of the mechanism used, e.g. "advisory topics". */
  source: string
  message: string
  note: string | null
}

/** A destination this instance has opened before, remembered server-side next to the connection. */
export interface SavedDestination {
  name: string
  kind: DestinationKind
  pinned: boolean
  openCount: number
  lastOpenedAt: string
  createdAt: string
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

/**
 * Presentation order only. A limited read always returns the most recent matching lines, so
 * `OLDEST_FIRST` reverses the page rather than choosing a different one.
 */
export type LogSort = 'NEWEST_FIRST' | 'OLDEST_FIRST'

/** A read of the in-memory ring buffer behind the monitoring page. */
export interface LogSnapshot {
  entries: LogEntry[]
  /** Lines currently in the buffer, matched or not. */
  held: number
  capacity: number
  /** Lines evicted since startup. Non-zero means the history shown is incomplete. */
  dropped: number
  /** The limit stopped the scan while older lines remained inside the requested range. */
  windowTruncated: boolean
  /** Echoed back, so the footer describes the answer rather than the request. */
  order: LogSort
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
