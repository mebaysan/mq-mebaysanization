import { useState } from 'react'

import { ApiError } from '../../api/client'
import { useSendMessage } from '../../api/queue'
import type { MessageType, TargetClient } from '../../api/types'
import { KeyValueEditor, type KeyValueRow } from '../../components/KeyValueEditor'
import { buttonClass, cardClass, inputClass } from '../../components/Primitives'
import { ChevronRightIcon, PlusIcon } from '../../components/icons'
import { useToast } from '../../components/ToastProvider'
import type { ProviderCaveat } from './ProviderCaveats'

/**
 * One labelled row of mutually exclusive pills.
 *
 * The label is visible, not only an `aria-label`: there are two of these on the form now, and two
 * unlabelled rows of pills sitting on top of each other say nothing about which is which.
 */
function ToggleGroup<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">{label}</span>
      <div className="flex items-center gap-1" role="group" aria-label={label}>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            onClick={() => onChange(option.value)}
            aria-pressed={value === option.value}
            className={`rounded-lg px-2 py-1 text-xs font-medium ${
              value === option.value
                ? 'bg-brand-600 text-white'
                : 'border border-line bg-surface text-fg-muted hover:bg-hover'
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
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
  const [targetClient, setTargetClient] = useState<TargetClient>('JMS')
  const [rows, setRows] = useState<KeyValueRow[]>([{ key: '', value: '' }])
  // Collapsed by default: the message list is what a user comes to see, and the composer is a
  // deliberate action. It opens on demand and stays open across sends within the same destination.
  const [open, setOpen] = useState(false)

  // The server refuses this combination outright rather than dropping the properties, so warn while
  // there is still something to fix. Not by hiding the rows: that would either discard what was typed
  // or produce a 400 whose cause is no longer on screen.
  const propertiesCannotTravel =
    caveat.hasTargetClient && targetClient === 'MQ' && rows.some((row) => row.key.trim() !== '')

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
        targetClient: caveat.hasTargetClient ? targetClient : null,
      },
      {
        onSuccess: (result) => {
          toast.success(`Sent. Message id ${result.messageId}`)
          setPayload('')
          setKey('')
          setMessageType('TEXT')
          // The target client deliberately survives the reset. A message type describes this message;
          // the target client describes the reader of this queue, which does not change between sends.
          // Clearing it would put the MQRFH2 header back on the very next send, silently reproducing
          // the failure the user had just worked out how to avoid.
          setRows([{ key: '', value: '' }])
        },
        onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
      },
    )
  }

  return (
    <form onSubmit={submit} className={`overflow-hidden ${cardClass}`}>
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-2 px-4 py-3 text-left transition-colors duration-150 hover:bg-hover"
      >
        <span className="flex items-center gap-2 text-sm font-semibold text-fg">
          <span className="grid h-6 w-6 place-items-center rounded-md bg-brand-50 text-brand-600">
            <PlusIcon size={14} />
          </span>
          Send a message
        </span>
        <ChevronRightIcon
          size={16}
          className={`text-fg-subtle transition-transform duration-200 ${open ? 'rotate-90' : ''}`}
        />
      </button>

      {open && (
        <div className="animate-fade-in border-t border-line p-4">
          <textarea
            className={`${inputClass} min-h-28 font-mono`}
            value={payload}
            onChange={(event) => setPayload(event.target.value)}
            placeholder="Message body"
          />

      {caveat.hasMessageType && (
        <div className="mt-2">
          <ToggleGroup
            label="Body type"
            value={messageType}
            options={[
              { value: 'TEXT', label: 'Text' },
              { value: 'BYTES', label: 'Bytes' },
            ]}
            onChange={setMessageType}
          />
          {messageType === 'BYTES' && (
            <p className="mt-1 text-xs text-fg-subtle">
              Sent as a JMS BytesMessage: the body above, UTF-8 encoded, written as raw bytes. Brokers
              convert this to an AMQP binary body, where a text message becomes an AMQP string — some
              AMQP 1.0 clients accept only the former.
            </p>
          )}
        </div>
      )}

      {caveat.hasTargetClient && (
        <div className="mt-2">
          <ToggleGroup
            label="IBM MQ header"
            value={targetClient}
            options={[
              { value: 'JMS', label: 'JMS (RFH2)' },
              { value: 'MQ', label: 'MQ (no header)' },
            ]}
            onChange={setTargetClient}
          />
          {targetClient === 'MQ' && (
            <p className="mt-1 text-xs text-fg-subtle">
              Put without an MQRFH2 header, so an application doing a native MQGET receives the body and
              nothing else. Choose this when a non-JMS reader fails to parse a message that looks
              perfectly fine here. Text puts it as MQSTR, converted to the reader's CCSID on request;
              Bytes puts it as MQFMT_NONE, which is byte-exact and never converted.
            </p>
          )}
        </div>
      )}

      {caveat.hasMessageKey && (
        <div className="mt-3">
          <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">Key</span>
          <input
            className={`${inputClass} mt-2 font-mono`}
            value={key}
            onChange={(event) => setKey(event.target.value)}
            placeholder="Optional"
          />
          <p className="mt-1 text-xs text-fg-subtle">
            Optional. Records sharing a key land on the same partition and stay in order relative to
            each other. Without one, Kafka spreads records across partitions.
          </p>
        </div>
      )}

      <div className="mt-3">
        <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">
          {caveat.propertiesLabel}
        </span>
        <div className="mt-2">
          <KeyValueEditor
            rows={rows}
            onChange={setRows}
            addLabel={caveat.hasMessageKey ? 'header' : 'property'}
          />
        </div>
        {propertiesCannotTravel && (
          <p className="mt-2 rounded-lg border border-amber-200 dark:border-amber-900/60 bg-amber-50 dark:bg-amber-950/40 px-3 py-2 text-xs text-amber-900 dark:text-amber-200">
            Custom properties travel in the MQRFH2 usr folder, and the MQ header setting is the
            instruction not to write an MQRFH2 at all. This send will be refused rather than dropping
            them: clear the rows above, or switch the header back to JMS.
          </p>
        )}
      </div>

          <div className="mt-4 flex justify-end">
            <button type="submit" className={buttonClass} disabled={send.isPending}>
              {send.isPending ? 'Sending…' : 'Send message'}
            </button>
          </div>
        </div>
      )}
    </form>
  )
}
