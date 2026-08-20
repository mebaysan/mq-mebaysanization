import { useState } from 'react'

import { useCreateTopic } from '../../api/destinations'
import { ApiError } from '../../api/client'
import { KeyValueEditor, type KeyValueRow } from '../../components/KeyValueEditor'
import { DialogTitle, Modal, ModalCloseButton } from '../../components/Modal'
import { buttonClass, Field, inputClass, secondaryButtonClass } from '../../components/Primitives'
import { useToast } from '../../components/ToastProvider'

interface CreateTopicDialogProps {
  open: boolean
  connectionId: number
  onClose: () => void
  /** Called with the created name so the page can open it straight away. */
  onCreated: (name: string) => void
}

/**
 * Creates a Kafka topic: a name, a partition count, a replication factor and any topic configs.
 *
 * <p>Only ever mounted for a Kafka connection — the JMS providers create a queue on first send or not
 * at all, so there is nothing here they could do, and the server refuses the call with a 501. The
 * configs use the same {@link KeyValueEditor} the send form does, so a block of `key=value` overrides
 * can be pasted in one go rather than typed a row at a time.
 *
 * <p>Partitions and replication default to 1: the values that work on any cluster, including the
 * single-broker one a developer runs locally. Both are stated rather than guessed on the server, which
 * is why they are sent explicitly.
 */
export function CreateTopicDialog({ open, connectionId, onClose, onCreated }: CreateTopicDialogProps) {
  const toast = useToast()
  const create = useCreateTopic(connectionId)

  const [name, setName] = useState('')
  const [partitions, setPartitions] = useState('1')
  const [replicationFactor, setReplicationFactor] = useState('1')
  const [configs, setConfigs] = useState<KeyValueRow[]>([{ key: '', value: '' }])

  const reset = () => {
    setName('')
    setPartitions('1')
    setReplicationFactor('1')
    setConfigs([{ key: '', value: '' }])
  }

  const partitionsNumber = Number(partitions)
  const replicationNumber = Number(replicationFactor)
  const valid =
    name.trim() !== '' &&
    Number.isInteger(partitionsNumber) &&
    partitionsNumber >= 1 &&
    Number.isInteger(replicationNumber) &&
    replicationNumber >= 1

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!valid) return

    const configMap = Object.fromEntries(
      configs.filter((row) => row.key.trim() !== '').map((row) => [row.key.trim(), row.value]),
    )

    create.mutate(
      {
        name: name.trim(),
        partitions: partitionsNumber,
        replicationFactor: replicationNumber,
        configs: configMap,
      },
      {
        onSuccess: (result) => {
          toast.success(result.note ? `${result.message} ${result.note}` : result.message)
          const created = result.name
          reset()
          onClose()
          onCreated(created)
        },
        onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
      },
    )
  }

  return (
    <Modal
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
      align="start"
      contentClassName="max-w-lg"
    >
      <form onSubmit={submit} className="flex flex-col">
        <div className="flex items-start justify-between gap-4 border-b border-line p-4">
          <div>
            <DialogTitle className="text-lg font-semibold tracking-tight text-fg">
              Create a topic
            </DialogTitle>
            <p className="mt-1 text-xs text-fg-subtle">
              Creates the topic on this cluster through the Kafka admin API.
            </p>
          </div>
          <ModalCloseButton />
        </div>

        <div className="space-y-4 p-4">
          <Field label="Topic name">
            <input
              className={`${inputClass} font-mono`}
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="orders"
              autoFocus
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field
              label="Partitions"
              hint="Can be raised later, never lowered."
            >
              <input
                type="number"
                min={1}
                className={inputClass}
                value={partitions}
                onChange={(event) => setPartitions(event.target.value)}
              />
            </Field>
            <Field label="Replication factor" hint="1 on a single-broker cluster.">
              <input
                type="number"
                min={1}
                className={inputClass}
                value={replicationFactor}
                onChange={(event) => setReplicationFactor(event.target.value)}
              />
            </Field>
          </div>

          <div>
            <span className="text-xs font-medium uppercase tracking-wide text-fg-subtle">
              Topic configs
            </span>
            <p className="mb-2 mt-1 text-xs text-fg-subtle">
              Optional overrides such as retention.ms or cleanup.policy. Paste a block of key=value
              lines with Bulk edit.
            </p>
            <KeyValueEditor
              rows={configs}
              onChange={setConfigs}
              addLabel="config"
              keyPlaceholder="retention.ms"
              valuePlaceholder="604800000"
              bulkPlaceholder="retention.ms=604800000&#10;cleanup.policy=compact"
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 border-t border-line bg-surface-2 px-4 py-3">
          <button type="button" className={secondaryButtonClass} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={buttonClass} disabled={!valid || create.isPending}>
            {create.isPending ? 'Creating…' : 'Create topic'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
