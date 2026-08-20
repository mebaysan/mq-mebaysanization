import { cva } from 'class-variance-authority'
import type { ReactNode } from 'react'

import type { Provider } from '../api/types'
import { AlertIcon } from './icons'

const PROVIDER_STYLES: Record<Provider, string> = {
  ACTIVE_MQ: 'bg-amber-50 text-amber-700 ring-amber-200',
  ARTEMIS: 'bg-violet-50 text-violet-700 ring-violet-200',
  IBM_MQ: 'bg-sky-50 text-sky-700 ring-sky-200',
  KAFKA: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
}

const PROVIDER_DOT: Record<Provider, string> = {
  ACTIVE_MQ: 'bg-amber-500',
  ARTEMIS: 'bg-violet-500',
  IBM_MQ: 'bg-sky-500',
  KAFKA: 'bg-emerald-500',
}

export function ProviderBadge({ provider, label }: { provider: Provider; label?: string }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${PROVIDER_STYLES[provider]}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${PROVIDER_DOT[provider]}`} aria-hidden="true" />
      {label ?? provider}
    </span>
  )
}

/** Renders a failed request, including the stable error code so the README can be searched for it. */
export function ErrorBanner({
  title = 'Something went wrong',
  message,
  code,
  onRetry,
}: {
  title?: string
  message: string
  code?: string
  onRetry?: () => void
}) {
  return (
    <div
      className="rounded-xl border border-rose-200 bg-rose-50 p-4 shadow-card dark:border-rose-900/60 dark:bg-rose-950/40"
      role="alert"
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          <AlertIcon size={18} className="mt-0.5 shrink-0 text-rose-500" />
          <div>
            <p className="text-sm font-semibold text-rose-900 dark:text-rose-100">{title}</p>
            <p className="mt-1 whitespace-pre-wrap text-sm text-rose-800 dark:text-rose-200">{message}</p>
            {code && (
              <p className="mt-2 inline-block rounded-md bg-rose-100 px-1.5 py-0.5 font-mono text-xs text-rose-700 dark:bg-rose-900/50 dark:text-rose-200">
                {code}
              </p>
            )}
          </div>
        </div>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="shrink-0 rounded-lg border border-rose-300 bg-surface px-3 py-1.5 text-sm font-medium text-rose-800 shadow-xs transition-colors duration-150 hover:bg-rose-100 focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-rose-500/20 dark:border-rose-900/60 dark:text-rose-200 dark:hover:bg-rose-900/40"
          >
            Retry
          </button>
        )}
      </div>
    </div>
  )
}

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2.5" aria-hidden="true">
      {Array.from({ length: rows }).map((_, index) => (
        <div
          key={index}
          className="h-10 animate-pulse rounded-lg bg-gradient-to-r from-surface-2 via-line to-surface-2"
        />
      ))}
    </div>
  )
}

export function EmptyState({
  title,
  body,
  action,
}: {
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-xl border border-dashed border-line bg-surface-2/60 p-10 text-center">
      <p className="text-sm font-semibold text-fg">{title}</p>
      <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-fg-muted">{body}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string
  hint?: string
  error?: string
  children: ReactNode
}) {
  return (
    <label className="block">
      <span className="block text-sm font-medium text-fg-muted">{label}</span>
      {children}
      {hint && !error && <span className="mt-1.5 block text-xs leading-relaxed text-fg-subtle">{hint}</span>}
      {error && <span className="mt-1.5 block text-xs text-rose-600">{error}</span>}
    </label>
  )
}

export const inputClass =
  'mt-1 w-full rounded-lg border border-line bg-surface px-3 py-2 text-sm text-fg ' +
  'shadow-xs transition-[color,box-shadow,border-color] duration-150 placeholder:text-fg-subtle ' +
  'hover:border-fg-subtle focus:border-brand-500 focus:outline-none focus:ring-4 ' +
  'focus:ring-brand-500/15 disabled:cursor-not-allowed disabled:bg-surface-2 disabled:text-fg-subtle'

/**
 * The one button recipe, as variants. Call sites keep using the string constants below, so nothing had
 * to change; new code can also call {@code button({ variant, size })} directly. Centralising it here is
 * the point — a tweak to the primary gradient or the focus ring lands in exactly one place.
 */
export const button = cva(
  'inline-flex items-center justify-center gap-1.5 rounded-lg text-sm font-medium shadow-xs ' +
    'transition-[filter,background-color,box-shadow,transform] duration-150 focus-visible:outline-none ' +
    'focus-visible:ring-4 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        primary:
          'bg-gradient-to-r from-brand-600 to-accent-600 text-white hover:brightness-110 ' +
          'focus-visible:ring-brand-500/25',
        secondary:
          'border border-line bg-surface text-fg-muted hover:bg-hover hover:text-fg ' +
          'focus-visible:ring-brand-500/20',
        danger:
          'bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-rose-500/25',
      },
      size: {
        md: 'px-3.5 py-2',
        sm: 'px-2.5 py-1.5 text-xs',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export const buttonClass = button()
export const secondaryButtonClass = button({ variant: 'secondary' })
export const dangerButtonClass = button({ variant: 'danger' })

/** A surface: the white rounded panel used across every page. Reuse it instead of re-typing the string. */
export const cardClass =
  'rounded-xl border border-line/80 bg-surface shadow-card'
