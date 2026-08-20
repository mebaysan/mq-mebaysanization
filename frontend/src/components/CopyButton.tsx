import { useEffect, useRef, useState } from 'react'

import { CheckIcon, CopyIcon } from './icons'

/**
 * A small "copy this to the clipboard" button that confirms it worked.
 *
 * <p>The confirmation matters: a copy button that looks identical before and after a click leaves the
 * user guessing whether anything happened, and on a payload they cannot see the length of, guessing is
 * all they have. It flips to "Copied" for a moment, then back.
 *
 * <p>`navigator.clipboard` needs a secure context, which `localhost` is but a plain-http LAN address is
 * not — this tool is often opened at `http://some-host:8080`. So there is a deliberate fallback through
 * a hidden textarea and `execCommand`, which works there too. Neither path throws into the UI.
 */
export function CopyButton({
  text,
  label = 'Copy',
  copiedLabel = 'Copied',
  title,
  className,
}: {
  /** Resolved lazily on click, so a parent can pass a getter for text that is expensive to build. */
  text: string | (() => string)
  label?: string
  copiedLabel?: string
  title?: string
  className?: string
}) {
  const [copied, setCopied] = useState(false)
  // Cleared on unmount so a click on a row that then collapses does not set state on a gone component.
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  const copy = async () => {
    const value = typeof text === 'function' ? text() : text
    const ok = await writeClipboard(value)
    if (!ok) return
    setCopied(true)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 1500)
  }

  return (
    <button
      type="button"
      onClick={copy}
      title={title ?? label}
      aria-label={title ?? label}
      className={`inline-flex items-center gap-1.5 transition-colors duration-150 ${
        copied ? 'text-emerald-600' : ''
      } ${
        className ??
        'rounded-md border border-line bg-surface px-2 py-1 text-xs font-medium text-fg-muted hover:bg-hover'
      }`}
    >
      {copied ? <CheckIcon size={13} /> : <CopyIcon size={13} />}
      {copied ? copiedLabel : label}
    </button>
  )
}

async function writeClipboard(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch {
    // Fall through to the execCommand path below rather than surfacing a clipboard-permission error.
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = value
    // Kept off-screen and unfocusable-looking, but still selectable, which execCommand requires.
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
