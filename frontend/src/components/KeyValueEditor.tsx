import { useState } from 'react'

import { CopyButton } from './CopyButton'
import { inputClass, secondaryButtonClass } from './Primitives'

export interface KeyValueRow {
  key: string
  value: string
}

/**
 * Serialises rows to the one-per-line `KEY=VALUE` form used by the bulk editor and the copy button.
 * Blank-keyed rows are dropped, since they carry nothing and would paste back as empty lines.
 */
export function rowsToText(rows: KeyValueRow[]): string {
  return rows
    .filter((row) => row.key.trim() !== '')
    .map((row) => `${row.key.trim()}=${row.value}`)
    .join('\n')
}

/**
 * Parses the bulk editor's text back into rows, IntelliJ-style: one `KEY=VALUE` per line, split on the
 * FIRST `=` so a value may itself contain one. Blank lines are skipped; a line with no `=` becomes a
 * key with an empty value, which is what a half-typed line should be rather than a dropped one.
 */
export function textToRows(text: string): KeyValueRow[] {
  return text
    .split('\n')
    .map((line) => {
      const trimmed = line.trim()
      if (trimmed === '') return null
      const eq = line.indexOf('=')
      if (eq === -1) return { key: trimmed, value: '' }
      return { key: line.slice(0, eq).trim(), value: line.slice(eq + 1).trim() }
    })
    .filter((row): row is KeyValueRow => row !== null)
}

/**
 * A controlled editor for a set of string key/value pairs, with two ways in: a row at a time, or a
 * paste-friendly bulk box.
 *
 * <p>The bulk box is the whole point of the second mode — pasting a block of `KEY=VALUE` lines copied
 * from somewhere else (an IntelliJ env-var dialog, a `.env` file, another message's headers) beats
 * typing a dozen rows by hand. Copy does the reverse, handing the current pairs back as that same text.
 *
 * <p>Rows are kept non-empty by the parent (it seeds `[{key:'',value:''}]`); this component preserves
 * that on the way out of bulk mode so the row editor never renders with nothing to type into.
 */
export function KeyValueEditor({
  rows,
  onChange,
  addLabel,
  keyPlaceholder = 'name',
  valuePlaceholder = 'value',
  bulkPlaceholder = 'KEY=VALUE, one per line',
}: {
  rows: KeyValueRow[]
  onChange: (rows: KeyValueRow[]) => void
  addLabel: string
  keyPlaceholder?: string
  valuePlaceholder?: string
  bulkPlaceholder?: string
}) {
  const [bulk, setBulk] = useState(false)
  // Held locally while editing so keystrokes never fight the parsed-and-reserialised round trip, which
  // would move the caret and eat a just-typed `=`.
  const [bulkText, setBulkText] = useState('')

  const enterBulk = () => {
    setBulkText(rowsToText(rows))
    setBulk(true)
  }

  const exitBulk = () => {
    const parsed = textToRows(bulkText)
    onChange(parsed.length > 0 ? parsed : [{ key: '', value: '' }])
    setBulk(false)
  }

  const onBulkChange = (text: string) => {
    setBulkText(text)
    // Live, so a parent that reacts to the pairs (e.g. a warning about them) stays in step while typing.
    onChange(textToRows(text))
  }

  const updateRow = (index: number, patch: Partial<KeyValueRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)))

  const nonEmpty = rows.some((row) => row.key.trim() !== '')

  return (
    <div>
      <div className="mb-2 flex items-center gap-2">
        <button
          type="button"
          className="text-xs font-medium text-brand-700 hover:underline"
          onClick={bulk ? exitBulk : enterBulk}
        >
          {bulk ? 'Edit as rows' : 'Bulk edit'}
        </button>
        {nonEmpty && (
          <CopyButton
            text={() => rowsToText(rows)}
            label="Copy all"
            title="Copy every pair as KEY=VALUE lines"
            className="text-xs font-medium text-brand-700 hover:underline"
          />
        )}
      </div>

      {bulk ? (
        <textarea
          className={`${inputClass} mt-0 min-h-28 font-mono`}
          value={bulkText}
          onChange={(event) => onBulkChange(event.target.value)}
          placeholder={bulkPlaceholder}
          aria-label="Bulk edit as KEY=VALUE lines"
        />
      ) : (
        <>
          <div className="space-y-2">
            {rows.map((row, index) => (
              <div key={index} className="flex gap-2">
                <input
                  className={`${inputClass} mt-0 flex-1`}
                  value={row.key}
                  onChange={(event) => updateRow(index, { key: event.target.value })}
                  placeholder={keyPlaceholder}
                />
                <input
                  className={`${inputClass} mt-0 flex-1`}
                  value={row.value}
                  onChange={(event) => updateRow(index, { value: event.target.value })}
                  placeholder={valuePlaceholder}
                />
                <button
                  type="button"
                  className={secondaryButtonClass}
                  onClick={() => onChange(rows.filter((_, i) => i !== index))}
                  aria-label={`Remove ${addLabel}`}
                  disabled={rows.length === 1}
                >
                  −
                </button>
              </div>
            ))}
          </div>
          <button
            type="button"
            className="mt-2 text-sm text-brand-700 hover:underline"
            onClick={() => onChange([...rows, { key: '', value: '' }])}
          >
            + Add {addLabel}
          </button>
        </>
      )}
    </div>
  )
}
