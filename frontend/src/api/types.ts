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
 * How a message body goes onto the wire, on the JMS providers only.
 *
 * `BYTES` produces a JMS BytesMessage, which a broker converts to an AMQP binary body; `TEXT` produces a
 * TextMessage, which crosses as an AMQP string. Some AMQP 1.0 clients accept only the former, so this is
 * never chosen on the user's behalf. Kafka rejects any value — its record values are bytes already.
 */
export type MessageType = 'TEXT' | 'BYTES'

/**
 * Whether IBM MQ writes an MQRFH2 header ahead of the body, on IBM MQ only.
 *
 * `JMS` is the client's own default and prefixes every message with an MQRFH2 carrying the `mcd`, `jms`
 * and `usr` folders. A JMS reader consumes that header; an application doing a native `MQGET` does not,
 * and receives it as the first bytes of its payload — which is why an XML parser fails at line 1,
 * column 0 on a body that looks perfectly well formed in the browse view. `MQ` suppresses it, so the
 * queue holds the body and nothing else.
 *
 * Independent of `MessageType`, and choosing `BYTES` never removed the header — it only changed
 * `<Msd>jms_text</Msd>` to `<Msd>jms_bytes</Msd>` inside it. The two compose: with `MQ`, `TEXT` puts the
 * message as `MQSTR` (convertible, in the destination CCSID) and `BYTES` as `MQFMT_NONE` (byte-exact).
 *
 * The other three providers reject any value, since none of them writes a header of its own.
 */
export type TargetClient = 'JMS' | 'MQ'

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

/**
 * A message-send request remembered for a destination, so it can be sent again without retyping.
 *
 * `named` is false for an automatic history entry (written on every send, capped per destination) and
 * true for one the user saved deliberately under a `label` (kept until forgotten). Shares the shape the
 * send panel composes — `key`, `messageType` and `targetClient` are null on providers without them.
 */
export interface SavedRequest {
  id: number
  label: string | null
  named: boolean
  payload: string
  properties: Record<string, string>
  key: string | null
  messageType: MessageType | null
  targetClient: TargetClient | null
  createdAt: string
}

/**
 * A request to create one topic. Kafka only — the JMS providers refuse it with a 501, so the UI only
 * ever offers it for a Kafka connection.
 */
export interface CreateTopicRequest {
  name: string
  partitions: number
  replicationFactor: number
  configs: Record<string, string>
}

export interface CreateTopicResult {
  name: string
  partitions: number
  replicationFactor: number
  message: string
  /** Provider-specific caveat, shown after the headline. */
  note: string | null
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
