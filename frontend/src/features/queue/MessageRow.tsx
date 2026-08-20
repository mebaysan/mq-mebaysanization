import { useState } from 'react'

import { ApiError } from '../../api/client'
import { useMessageDetail } from '../../api/queue'
import type { QueueMessage } from '../../api/types'
import { CopyButton } from '../../components/CopyButton'
import { secondaryButtonClass } from '../../components/Primitives'

/** Entries as one-per-line `key=value`, matching the bulk-edit format the send form reads back. */
function entriesToText(entries: Record<string, string>): string {
  return Object.entries(entries)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
}

function formatTime(value: string | null): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString()
}

function preview(body: string | null): string {
  if (!body) return ''
  const firstLine = body.split('\n', 1)[0] ?? ''
  return firstLine.length > 90 ? `${firstLine.slice(0, 90)}…` : firstLine
}

function DefinitionList({ title, entries }: { title: string; entries: Record<string, string> }) {
  const keys = Object.keys(entries)
  if (keys.length === 0) {
    return (
      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">{title}</h4>
        <p className="mt-1 text-sm text-fg-subtle">None.</p>
      </div>
    )
  }
  return (
    <div>
      <div className="flex items-center justify-between gap-2">
        <h4 className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">{title}</h4>
        <CopyButton
          text={() => entriesToText(entries)}
          label="Copy"
          title={`Copy ${title.toLowerCase()} as KEY=VALUE lines`}
          className="text-xs font-medium text-brand-700 hover:underline"
        />
      </div>
      <dl className="mt-1 grid grid-cols-[minmax(0,12rem)_1fr] gap-x-3 gap-y-1 text-sm">
        {keys.map((key) => (
          <div key={key} className="contents">
            <dt className="truncate font-mono text-xs text-fg-subtle">{key}</dt>
            <dd className="break-all font-mono text-xs text-fg">{entries[key]}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

export function MessageRow({
  connectionId,
  queueName,
  message,
  onDelete,
}: {
  connectionId: number
  queueName: string
  message: QueueMessage
  /**
   * Null when the provider cannot delete a single message. The button is then not rendered at all,
   * rather than rendered disabled: a greyed-out control implies the feature exists and is merely
   * unavailable right now, and on Kafka it can never exist — a partition is an append-only log.
   */
  onDelete: ((message: QueueMessage) => void) | null
}) {
  const [expanded, setExpanded] = useState(false)

  // Only fetched on expand, and only when the list view had to cut the body short.
  const detail = useMessageDetail(
    connectionId,
    queueName,
    message.messageId,
    expanded && message.bodyTruncated,
  )

  const fullBody = detail.data?.body ?? message.body
  const stillTruncated = message.bodyTruncated && !detail.data

  // Off by default: pretty-printing every row would push the list far down the page, so the user opts in
  // per message. Only offered when the body actually parses as a JSON object/array — nothing to "beautify"
  // about a bare string or a non-JSON payload.
  const [pretty, setPretty] = useState(false)
  const prettyBody = (() => {
    if (!fullBody) return null
    try {
      const parsed = JSON.parse(fullBody)
      return parsed && typeof parsed === 'object' ? JSON.stringify(parsed, null, 2) : null
    } catch {
      return null
    }
  })()
  const displayBody = pretty && prettyBody ? prettyBody : (fullBody ?? '(no body)')

  return (
    <>
      <tr
        onClick={() => setExpanded((value) => !value)}
        aria-expanded={expanded}
        className="cursor-pointer transition-colors duration-150 hover:bg-hover/70"
      >
        <td className="px-3 py-2 align-top">
          {/* The whole row toggles now, so the id is a plain affordance rather than the only target. */}
          <span className="font-mono text-xs text-brand-700">
            {expanded ? '▾' : '▸'} {message.messageId}
          </span>
        </td>
        <td className="px-3 py-2 align-top text-xs text-fg-muted">
          {formatTime(message.enqueueTime)}
          {/* Shown verbatim: this is JMSType when the sender set one and the message class otherwise,
              so guessing at a friendlier label would sometimes rename the user's own value. */}
          {message.type && (
            <span className="mt-0.5 block font-mono text-[11px] text-fg-subtle" title={message.type}>
              {message.type}
            </span>
          )}
        </td>
        <td className="px-3 py-2 align-top font-mono text-xs text-fg-muted">
          {preview(message.body)}
          {message.bodyTruncated && <span className="ml-1 text-fg-subtle">(truncated)</span>}
        </td>
        {onDelete && (
          <td
            className="px-3 py-2 text-right align-top"
            onClick={(event) => event.stopPropagation()}
          >
            <button type="button" className={secondaryButtonClass} onClick={() => onDelete(message)}>
              Delete
            </button>
          </td>
        )}
      </tr>

      {expanded && (
        <tr className="bg-surface-2/70">
          <td colSpan={onDelete ? 4 : 3} className="px-3 pb-4 pt-1">
            <div className="space-y-4">
              {message.note && (
                <p className="rounded-md border border-amber-200 dark:border-amber-900/60 bg-amber-50 dark:bg-amber-950/40 px-3 py-2 text-xs text-amber-900 dark:text-amber-200">
                  {message.note}
                </p>
              )}

              <div>
                <div className="flex items-center justify-between gap-2">
                  <h4 className="text-xs font-semibold uppercase tracking-wide text-fg-subtle">Body</h4>
                  <div className="flex items-center gap-3">
                    {prettyBody && (
                      <button
                        type="button"
                        onClick={() => setPretty((value) => !value)}
                        aria-pressed={pretty}
                        className="text-xs font-medium text-brand-700 hover:underline"
                        title={pretty ? 'Show the raw payload' : 'Pretty-print the JSON payload'}
                      >
                        {pretty ? 'Raw' : 'Beautify JSON'}
                      </button>
                    )}
                    {fullBody != null && fullBody !== '' && (
                      <CopyButton
                        text={() => displayBody}
                        label="Copy payload"
                        title="Copy the message body"
                        className="text-xs font-medium text-brand-700 hover:underline"
                      />
                    )}
                  </div>
                </div>
                {detail.isPending && detail.isFetching && (
                  <p className="mt-1 text-sm text-fg-subtle">Loading the full body…</p>
                )}
                {detail.isError && (
                  <p className="mt-1 text-sm text-rose-700">
                    {detail.error instanceof ApiError ? detail.error.message : 'Could not load the body.'}
                  </p>
                )}
                <pre className="mt-1 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded-md border border-line bg-surface p-3 font-mono text-xs text-fg">
                  {displayBody}
                </pre>
                {stillTruncated && !detail.isError && (
                  <p className="mt-1 text-xs text-fg-subtle">
                    Showing the preview; the full body is still loading.
                  </p>
                )}
              </div>

              <DefinitionList title="Headers" entries={detail.data?.headers ?? message.headers} />
              <DefinitionList
                title="Properties"
                entries={detail.data?.properties ?? message.properties}
              />
            </div>
          </td>
        </tr>
      )}
    </>
  )
}
