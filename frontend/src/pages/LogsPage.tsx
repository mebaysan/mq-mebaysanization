import { useState } from 'react'

import { ApiError } from '../api/client'
import { useClearLogs, useLogs } from '../api/logs'
import type { LogEntry, LogLevel, LogSort } from '../api/types'
import { ConfirmDialog } from '../components/ConfirmDialog'
import {
  EmptyState,
  ErrorBanner,
  Skeleton,
  inputClass,
  secondaryButtonClass,
} from '../components/Primitives'
import { useToast } from '../components/ToastProvider'

const LEVELS: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']
const LIMIT = 300
const REFRESH_MS = 3000

/** Every preset sets a start and clears the end, so the Live toggle keeps working after one. */
const PRESETS: { label: string; minutes: number | 'today' | null }[] = [
  { label: 'Last 5 min', minutes: 5 },
  { label: 'Last 15 min', minutes: 15 },
  { label: 'Last hour', minutes: 60 },
  { label: 'Today', minutes: 'today' },
  { label: 'All', minutes: null },
]

/** Severity carries most of the meaning on this page, so it is the only thing that gets colour. */
const LEVEL_STYLES: Record<LogLevel, string> = {
  TRACE: 'bg-slate-100 text-slate-600 ring-slate-200',
  DEBUG: 'bg-slate-100 text-slate-700 ring-slate-200',
  INFO: 'bg-sky-100 text-sky-800 ring-sky-200',
  WARN: 'bg-amber-100 text-amber-800 ring-amber-200',
  ERROR: 'bg-rose-100 text-rose-800 ring-rose-200',
}

/**
 * Fixed 24-hour HH:mm:ss.SSS in local time.
 *
 * <p>Not toLocaleTimeString(): in a 12-hour locale that yields "1:40:07 AM", so appending
 * milliseconds produces "1:40:07 AM.177". A fixed-width field also keeps the column aligned and
 * sorts the way a log reader expects.
 */
function formatTime(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) {
    return iso
  }
  const pad = (value: number, width = 2) => String(value).padStart(width, '0')
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}.${pad(at.getMilliseconds(), 3)}`
}

/**
 * A `datetime-local` value is local wall-clock and carries no zone. `new Date(value)` parses that
 * zone-less date-and-time form as LOCAL time, which is exactly what is wanted here.
 *
 * The end bound is rounded up to the end of its second: the input yields whole seconds, so an upper
 * bound of 14:03:11 must still include a line stamped 14:03:11.500 or the field would quietly drop it.
 */
function localInputToInstant(value: string, edge: 'start' | 'end'): string {
  if (value === '') {
    return ''
  }
  const at = new Date(value)
  if (Number.isNaN(at.getTime())) {
    return ''
  }
  at.setMilliseconds(edge === 'end' ? 999 : 0)
  return at.toISOString()
}

/**
 * The inverse, used to fill the fields from a preset.
 *
 * Deliberately NOT `at.toISOString().slice(0, 19)`: that is the UTC wall clock written into a
 * local-time field, which looks right in London and is silently wrong by hours everywhere else.
 */
function instantToLocalInput(at: Date): string {
  const pad = (value: number, width = 2) => String(value).padStart(width, '0')
  return (
    `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T` +
    `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`
  )
}

function LogRow({ entry }: { entry: LogEntry }) {
  const [expanded, setExpanded] = useState(false)
  const hasTrace = entry.stackTrace !== null

  return (
    <>
      <tr className="align-top hover:bg-slate-50">
        <td className="whitespace-nowrap px-3 py-1.5 font-mono text-xs text-slate-500">
          {formatTime(entry.timestamp)}
        </td>
        <td className="px-3 py-1.5">
          <span
            className={`inline-flex rounded px-1.5 py-0.5 text-[11px] font-medium ring-1 ring-inset ${LEVEL_STYLES[entry.level]}`}
          >
            {entry.level}
          </span>
        </td>
        <td
          className="max-w-[16rem] truncate px-3 py-1.5 font-mono text-xs text-slate-600"
          title={entry.logger}
        >
          {entry.logger}
        </td>
        <td className="px-3 py-1.5 text-sm text-slate-800">
          <span className="whitespace-pre-wrap break-words">{entry.message}</span>
          {hasTrace && (
            <button
              type="button"
              onClick={() => setExpanded((value) => !value)}
              className="ml-2 align-baseline text-xs text-brand-700 hover:underline"
              aria-expanded={expanded}
            >
              {expanded ? 'hide stack trace' : 'stack trace'}
            </button>
          )}
          <span className="mt-0.5 block font-mono text-[11px] text-slate-400">{entry.thread}</span>
        </td>
      </tr>
      {expanded && hasTrace && (
        <tr className="bg-slate-50/70">
          <td colSpan={4} className="px-3 pb-3 pt-0">
            <pre className="max-h-80 overflow-auto rounded-lg border border-slate-200 bg-white p-3 font-mono text-[11px] leading-5 text-slate-700">
              {entry.stackTrace}
            </pre>
          </td>
        </tr>
      )}
    </>
  )
}

export default function LogsPage() {
  const toast = useToast()

  const [level, setLevel] = useState<LogLevel>('INFO')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<LogSort>('NEWEST_FIRST')
  // Held as the LOCAL input strings, so the fields round-trip losslessly; the instants are derived.
  const [fromInput, setFromInput] = useState('')
  const [toInput, setToInput] = useState('')
  const [liveWanted, setLiveWanted] = useState(true)
  const [confirmClear, setConfirmClear] = useState(false)

  // With an end time set, no new line can ever enter the view. Polling would burn requests redrawing
  // an identical list and imply a liveness that does not exist, so it is refused rather than ignored.
  const canBeLive = toInput === ''
  const live = liveWanted && canBeLive

  const logs = useLogs(
    {
      level,
      query,
      from: localInputToInstant(fromInput, 'start'),
      to: localInputToInstant(toInput, 'end'),
      sort,
      limit: LIMIT,
    },
    live ? REFRESH_MS : false,
  )
  const clear = useClearLogs()

  const snapshot = logs.data
  const hasFilter = query.trim() !== '' || fromInput !== '' || toInput !== ''

  const applyPreset = (minutes: number | 'today' | null) => {
    setToInput('')
    if (minutes === null) {
      setFromInput('')
      return
    }
    const at = new Date()
    if (minutes === 'today') {
      at.setHours(0, 0, 0, 0)
    } else {
      at.setMinutes(at.getMinutes() - minutes)
    }
    setFromInput(instantToLocalInput(at))
  }

  const runClear = () => {
    clear.mutate(undefined, {
      onSuccess: () => {
        toast.success('Cleared the in-memory log buffer.')
        setConfirmClear(false)
      },
      onError: (err) => {
        toast.error(err instanceof ApiError ? err.message : String(err))
        setConfirmClear(false)
      },
    })
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-900">Logs</h1>
        <p className="mt-1 max-w-3xl text-sm text-slate-600">
          The most recent log lines from this process, held in memory. This is a live view for
          watching an operation as it happens — it is not an audit trail, and it is emptied on
          restart. Everything is also written to standard output, which is where anything permanent
          should be collected from.
        </p>
      </div>

      <p className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
        This build has no login, so anyone who can reach this page can read these lines. Passwords are
        never logged, but hostnames and queue and topic names are — and message bodies are one log
        level away: <code className="font-mono text-xs">MQMANAGER_LOG_PAYLOADS</code> ships enabled, so
        raising this application&rsquo;s log level to DEBUG puts every message you send onto this page.
        Set it to <code className="font-mono text-xs">false</code> to keep bodies out at any level.
      </p>

      <div className="space-y-3 rounded-xl border border-slate-200 bg-white p-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-1" role="group" aria-label="Minimum level">
            {LEVELS.map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setLevel(option)}
                aria-pressed={level === option}
                className={`rounded-lg px-2 py-1 text-xs font-medium ${
                  level === option
                    ? 'bg-brand-600 text-white'
                    : 'border border-slate-300 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                {option}
              </button>
            ))}
          </div>

          <input
            className={`${inputClass} mt-0 w-56 flex-1`}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Filter by message or logger"
            aria-label="Filter logs"
          />

          <label
            className={`flex items-center gap-2 text-sm ${
              canBeLive ? 'text-slate-700' : 'cursor-not-allowed text-slate-400'
            }`}
            title={
              canBeLive
                ? undefined
                : 'An end time is set, so no new line can enter this range.'
            }
          >
            <input
              type="checkbox"
              checked={live}
              disabled={!canBeLive}
              onChange={(event) => setLiveWanted(event.target.checked)}
              className="rounded border-slate-300"
            />
            Live
          </label>

          <button
            type="button"
            className={secondaryButtonClass}
            onClick={() => void logs.refetch()}
            disabled={logs.isFetching}
          >
            {logs.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>

          <button
            type="button"
            className={secondaryButtonClass}
            onClick={() => setConfirmClear(true)}
            disabled={clear.isPending}
          >
            Clear
          </button>
        </div>

        <div className="flex flex-wrap items-end gap-3 border-t border-slate-100 pt-3">
          <label className="text-xs font-medium text-slate-600">
            From
            <input
              type="datetime-local"
              step={1}
              className={`${inputClass} mt-1 block w-56`}
              value={fromInput}
              onChange={(event) => setFromInput(event.target.value)}
            />
          </label>

          <label className="text-xs font-medium text-slate-600">
            To
            <input
              type="datetime-local"
              step={1}
              className={`${inputClass} mt-1 block w-56`}
              value={toInput}
              onChange={(event) => setToInput(event.target.value)}
            />
          </label>

          <div className="flex flex-wrap items-center gap-1 pb-1" role="group" aria-label="Time range">
            {PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                onClick={() => applyPreset(preset.minutes)}
                className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50"
              >
                {preset.label}
              </button>
            ))}
          </div>

          <p className="pb-1 text-xs text-slate-500">
            Local time. The end bound includes the whole second.
          </p>
        </div>
      </div>

      {logs.isError && (
        <ErrorBanner
          title="Could not read the logs"
          message={logs.error instanceof ApiError ? logs.error.message : String(logs.error)}
          code={logs.error instanceof ApiError ? logs.error.code : undefined}
          onRetry={() => void logs.refetch()}
        />
      )}

      {logs.isPending && !logs.isError && <Skeleton rows={6} />}

      {snapshot && snapshot.entries.length === 0 && (
        <EmptyState
          title="Nothing to show"
          body={
            hasFilter || level !== 'TRACE'
              ? 'No line matches the current level, text filter and time range. Lower the level, ' +
                'clear the filter, or widen the range — remember the range is in local time.'
              : 'No log lines have been captured yet. Use the app and they will appear here.'
          }
        />
      )}

      {snapshot && snapshot.entries.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
          <div className="max-h-[34rem] overflow-auto">
            <table className="w-full text-left">
              <thead className="sticky top-0 border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th
                    className="px-3 py-2 font-medium"
                    aria-sort={sort === 'OLDEST_FIRST' ? 'ascending' : 'descending'}
                  >
                    <button
                      type="button"
                      onClick={() =>
                        setSort((current) =>
                          current === 'NEWEST_FIRST' ? 'OLDEST_FIRST' : 'NEWEST_FIRST',
                        )
                      }
                      className="inline-flex items-center gap-1 uppercase tracking-wide hover:text-slate-700"
                      title="Change the order these lines are shown in"
                    >
                      Time
                      <span aria-hidden="true">{sort === 'NEWEST_FIRST' ? '↓' : '↑'}</span>
                    </button>
                  </th>
                  <th className="px-3 py-2 font-medium">Level</th>
                  <th className="px-3 py-2 font-medium">Logger</th>
                  <th className="px-3 py-2 font-medium">Message</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {snapshot.entries.map((entry) => (
                  <LogRow key={entry.sequence} entry={entry} />
                ))}
              </tbody>
            </table>
          </div>
          <div className="border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-600">
            {snapshot.order === 'NEWEST_FIRST' ? 'Newest first.' : 'Oldest first.'} Showing{' '}
            {snapshot.entries.length} of {snapshot.held} line{snapshot.held === 1 ? '' : 's'} held, out
            of a {snapshot.capacity} capacity.
            {/* Without this, an oldest-first page starting at 10:03:11 would read as "nothing
                happened before then" rather than "we stopped looking there". */}
            {snapshot.windowTruncated && (
              <span className="ml-1 text-amber-700">
                These are the {snapshot.entries.length} most recent matching lines; older lines inside
                this range are not shown. Narrow the range to see them.
              </span>
            )}
            {snapshot.dropped > 0 && (
              <span className="ml-1 text-amber-700">
                {snapshot.dropped} older line{snapshot.dropped === 1 ? ' has' : 's have'} been
                dropped — this is not the whole history.
              </span>
            )}
            {!canBeLive && (
              <span className="ml-1 text-slate-500">
                Paused: an end time is set, so no new line can enter this range.
              </span>
            )}
            {live && <span className="ml-1 text-slate-500">Refreshing every {REFRESH_MS / 1000}s.</span>}
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmClear}
        title="Clear the log buffer?"
        body={
          <>
            This empties only what this page shows. Standard output is untouched, so nothing that
            collects logs from there loses anything.
          </>
        }
        confirmLabel="Clear buffer"
        busy={clear.isPending}
        onConfirm={runClear}
        onCancel={() => setConfirmClear(false)}
      />
    </div>
  )
}
