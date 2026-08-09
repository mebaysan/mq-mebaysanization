import { useState } from 'react'

import { ApiError } from '../../api/client'
import { useMessageDetail } from '../../api/queue'
import type { QueueMessage } from '../../api/types'
import { secondaryButtonClass } from '../../components/Primitives'

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
        <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h4>
        <p className="mt-1 text-sm text-slate-500">None.</p>
      </div>
    )
  }
  return (
    <div>
      <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h4>
      <dl className="mt-1 grid grid-cols-[minmax(0,12rem)_1fr] gap-x-3 gap-y-1 text-sm">
        {keys.map((key) => (
          <div key={key} className="contents">
            <dt className="truncate font-mono text-xs text-slate-500">{key}</dt>
            <dd className="break-all font-mono text-xs text-slate-800">{entries[key]}</dd>
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

  return (
    <>
      <tr className="hover:bg-slate-50">
        <td className="px-3 py-2 align-top">
          <button
            type="button"
            onClick={() => setExpanded((value) => !value)}
            className="font-mono text-xs text-brand-700 hover:underline"
            aria-expanded={expanded}
          >
            {expanded ? '▾' : '▸'} {message.messageId}
          </button>
        </td>
        <td className="px-3 py-2 align-top text-xs text-slate-600">
          {formatTime(message.enqueueTime)}
          {/* Shown verbatim: this is JMSType when the sender set one and the message class otherwise,
              so guessing at a friendlier label would sometimes rename the user's own value. */}
          {message.type && (
            <span className="mt-0.5 block font-mono text-[11px] text-slate-400" title={message.type}>
              {message.type}
            </span>
          )}
        </td>
        <td className="px-3 py-2 align-top font-mono text-xs text-slate-700">
          {preview(message.body)}
          {message.bodyTruncated && <span className="ml-1 text-slate-400">(truncated)</span>}
        </td>
        {onDelete && (
          <td className="px-3 py-2 text-right align-top">
            <button type="button" className={secondaryButtonClass} onClick={() => onDelete(message)}>
              Delete
            </button>
          </td>
        )}
      </tr>

      {expanded && (
        <tr className="bg-slate-50/70">
          <td colSpan={onDelete ? 4 : 3} className="px-3 pb-4 pt-1">
            <div className="space-y-4">
              {message.note && (
                <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
                  {message.note}
                </p>
              )}

              <div>
                <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Body</h4>
                {detail.isPending && detail.isFetching && (
                  <p className="mt-1 text-sm text-slate-500">Loading the full body…</p>
                )}
                {detail.isError && (
                  <p className="mt-1 text-sm text-rose-700">
                    {detail.error instanceof ApiError ? detail.error.message : 'Could not load the body.'}
                  </p>
                )}
                <pre className="mt-1 max-h-96 overflow-auto whitespace-pre-wrap break-all rounded-md border border-slate-200 bg-white p-3 font-mono text-xs text-slate-800">
                  {fullBody ?? '(no body)'}
                </pre>
                {stillTruncated && !detail.isError && (
                  <p className="mt-1 text-xs text-slate-500">
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
