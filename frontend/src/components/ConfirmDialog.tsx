import type { ReactNode } from 'react'

import { Modal, DialogTitle } from './Modal'
import { buttonClass, dangerButtonClass, secondaryButtonClass } from './Primitives'

interface ConfirmDialogProps {
  open: boolean
  title: string
  body: ReactNode
  confirmLabel: string
  destructive?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Every irreversible action in this tool goes through here — delete, purge, delete-message. */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  destructive = true,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      // Escape, a click outside, or Cancel all resolve to the same "not confirmed" outcome.
      onOpenChange={(next) => {
        if (!next) onCancel()
      }}
      align="center"
      contentClassName="max-w-md p-5"
    >
      <DialogTitle className="text-lg font-semibold tracking-tight text-fg">
        {title}
      </DialogTitle>
      <div className="mt-2 text-sm leading-relaxed text-fg-muted">{body}</div>
      <div className="mt-6 flex justify-end gap-2">
        <button type="button" onClick={onCancel} disabled={busy} className={secondaryButtonClass}>
          Cancel
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={busy}
          className={destructive ? dangerButtonClass : buttonClass}
        >
          {busy ? 'Working…' : confirmLabel}
        </button>
      </div>
    </Modal>
  )
}
