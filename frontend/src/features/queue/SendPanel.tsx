import { useState } from 'react'

import { ApiError } from '../../api/client'
import { useSendMessage } from '../../api/queue'
import type { MessageType } from '../../api/types'
import { buttonClass, inputClass, secondaryButtonClass } from '../../components/Primitives'
import { useToast } from '../../components/ToastProvider'
import type { ProviderCaveat } from './ProviderCaveats'

interface PropertyRow {
  key: string
  value: string
}

export function SendPanel({
  connectionId,
  queueName,
  caveat,
}: {
  connectionId: number
  queueName: string
  caveat: ProviderCaveat
}) {
  const toast = useToast()
  const send = useSendMessage(connectionId, queueName)

  const [payload, setPayload] = useState('')
  const [key, setKey] = useState('')
  const [messageType, setMessageType] = useState<MessageType>('TEXT')
  const [rows, setRows] = useState<PropertyRow[]>([{ key: '', value: '' }])

  const updateRow = (index: number, patch: Partial<PropertyRow>) =>
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)))

  const submit = (event: React.FormEvent) => {
    event.preventDefault()

    const properties = Object.fromEntries(
      rows.filter((row) => row.key.trim() !== '').map((row) => [row.key.trim(), row.value]),
    )

    send.mutate(
      // Null, not '', for a provider without keys: the server rejects a non-null key outright rather
      // than dropping it, which is what makes "the key was honoured" always true when one is sent.
      // Same reasoning for the message type: null, not 'TEXT', where the provider has no such concept.
      {
        payload,
        properties,
        key: caveat.hasMessageKey ? key.trim() || null : null,
        messageType: caveat.hasMessageType ? messageType : null,
      },
      {
        onSuccess: (result) => {
          toast.success(`Sent. Message id ${result.messageId}`)
          setPayload('')
          setKey('')
          setMessageType('TEXT')
          setRows([{ key: '', value: '' }])
        },
        onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
      },
    )
  }

  return (
    <form onSubmit={submit} className="rounded-xl border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900">Send a message</h2>

      <textarea
        className={`${inputClass} min-h-28 font-mono`}
        value={payload}
        onChange={(event) => setPayload(event.target.value)}
        placeholder="Message body"
      />

      {caveat.hasMessageType && (
        <div className="mt-2">
          <div className="flex items-center gap-1" role="group" aria-label="Body type">
            {(['TEXT', 'BYTES'] as MessageType[]).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setMessageType(option)}
                aria-pressed={messageType === option}
                className={`rounded-lg px-2 py-1 text-xs font-medium ${
                  messageType === option
                    ? 'bg-brand-600 text-white'
                    : 'border border-slate-300 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                {option === 'TEXT' ? 'Text' : 'Bytes'}
              </button>
            ))}
          </div>
          {messageType === 'BYTES' && (
            <p className="mt-1 text-xs text-slate-500">
              Sent as a JMS BytesMessage: the body above, UTF-8 encoded, written as raw bytes. Brokers
              convert this to an AMQP binary body, where a text message becomes an AMQP string — some
              AMQP 1.0 clients accept only the former.
            </p>
          )}
        </div>
      )}

      {caveat.hasMessageKey && (
        <div className="mt-3">
          <span className="text-xs font-medium uppercase tracking-wide text-slate-500">Key</span>
          <input
            className={`${inputClass} mt-2 font-mono`}
            value={key}
            onChange={(event) => setKey(event.target.value)}
            placeholder="Optional"
          />
          <p className="mt-1 text-xs text-slate-500">
            Optional. Records sharing a key land on the same partition and stay in order relative to
            each other. Without one, Kafka spreads records across partitions.
          </p>
        </div>
      )}

      <div className="mt-3">
        <span className="text-xs font-medium uppercase tracking-wide text-slate-500">
          {caveat.propertiesLabel}
        </span>
        <div className="mt-2 space-y-2">
          {rows.map((row, index) => (
            <div key={index} className="flex gap-2">
              <input
                className={`${inputClass} mt-0 flex-1`}
                value={row.key}
                onChange={(event) => updateRow(index, { key: event.target.value })}
                placeholder="name"
              />
              <input
                className={`${inputClass} mt-0 flex-1`}
                value={row.value}
                onChange={(event) => updateRow(index, { value: event.target.value })}
                placeholder="value"
              />
              <button
                type="button"
                className={secondaryButtonClass}
                onClick={() => setRows((current) => current.filter((_, i) => i !== index))}
                aria-label={`Remove ${caveat.hasMessageKey ? 'header' : 'property'}`}
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
          onClick={() => setRows((current) => [...current, { key: '', value: '' }])}
        >
          + Add {caveat.hasMessageKey ? 'header' : 'property'}
        </button>
      </div>

      <div className="mt-4 flex justify-end">
        <button type="submit" className={buttonClass} disabled={send.isPending}>
          {send.isPending ? 'Sending…' : 'Send message'}
        </button>
      </div>
    </form>
  )
}
