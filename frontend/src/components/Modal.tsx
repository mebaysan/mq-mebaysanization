import * as Dialog from '@radix-ui/react-dialog'
import type { ReactNode } from 'react'

import { cn } from '../lib/cn'
import { XIcon } from './icons'

/**
 * The shared modal shell, on Radix Dialog.
 *
 * <p>Radix gives us the parts a hand-rolled modal always gets wrong: a focus trap, Escape to close,
 * background scroll-lock, {@code aria-modal} wiring, and restoring focus to the trigger on close. We keep
 * the app's own look — blurred overlay that fades, panel that scales in, soft overlay shadow — and let
 * each caller draw its own header/body/footer inside.
 *
 * <p>Callers MUST include a {@link DialogTitle} somewhere inside (Radix warns otherwise); it names the
 * dialog for assistive tech. {@code onOpenChange(false)} fires on Escape, on a click outside, and on the
 * close button, so a single handler covers every dismissal.
 */
export function Modal({
  open,
  onOpenChange,
  children,
  contentClassName,
  align = 'start',
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: ReactNode
  /** Sizing/padding for the panel, e.g. "max-w-md p-5". */
  contentClassName?: string
  /** Vertical placement: "start" (top, for tall lists) or "center" (for short confirmations). */
  align?: 'start' | 'center'
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="animate-fade-in fixed inset-0 z-40 bg-slate-900/40 backdrop-blur-sm" />
        <div
          className={cn(
            'fixed inset-0 z-40 flex justify-center overflow-y-auto p-4 sm:p-8',
            align === 'center' ? 'items-center' : 'items-start',
          )}
        >
          <Dialog.Content
            className={cn(
              'animate-scale-in relative my-auto w-full rounded-2xl border border-line/60 bg-surface shadow-overlay focus:outline-none',
              contentClassName,
            )}
          >
            {children}
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** The dialog's accessible name. Wrap a heading with {@code asChild}, or use it directly. */
export const DialogTitle = Dialog.Title

/** A ready-made close button (the ✕) wired to Radix's dismiss. */
export function ModalCloseButton() {
  return (
    <Dialog.Close
      className="rounded-lg p-1.5 text-fg-subtle transition-colors hover:bg-hover hover:text-fg-muted focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-brand-500/20"
      aria-label="Close"
    >
      <XIcon size={18} />
    </Dialog.Close>
  )
}
