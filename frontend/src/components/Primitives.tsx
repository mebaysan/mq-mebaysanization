import type { ReactNode } from 'react'

import type { Provider } from '../api/types'

const PROVIDER_STYLES: Record<Provider, string> = {
  ACTIVE_MQ: 'bg-amber-100 text-amber-800 ring-amber-200',
  ARTEMIS: 'bg-violet-100 text-violet-800 ring-violet-200',
  IBM_MQ: 'bg-sky-100 text-sky-800 ring-sky-200',
  KAFKA: 'bg-emerald-100 text-emerald-800 ring-emerald-200',
}

export function ProviderBadge({ provider, label }: { provider: Provider; label?: string }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${PROVIDER_STYLES[provider]}`}
    >
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
    <div className="rounded-lg border border-rose-200 bg-rose-50 p-4" role="alert">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-rose-900">{title}</p>
          <p className="mt-1 whitespace-pre-wrap text-sm text-rose-800">{message}</p>
          {code && <p className="mt-2 font-mono text-xs text-rose-700">{code}</p>}
        </div>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="shrink-0 rounded-lg border border-rose-300 bg-white px-3 py-1.5 text-sm font-medium text-rose-800 hover:bg-rose-100"
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
    <div className="space-y-2" aria-hidden="true">
      {Array.from({ length: rows }).map((_, index) => (
        <div key={index} className="h-10 animate-pulse rounded-lg bg-slate-100" />
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
    <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-10 text-center">
      <p className="text-sm font-semibold text-slate-800">{title}</p>
      <p className="mx-auto mt-1 max-w-md text-sm text-slate-600">{body}</p>
      {action && <div className="mt-4">{action}</div>}
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
      <span className="block text-sm font-medium text-slate-700">{label}</span>
      {children}
      {hint && !error && <span className="mt-1 block text-xs text-slate-500">{hint}</span>}
      {error && <span className="mt-1 block text-xs text-rose-600">{error}</span>}
    </label>
  )
}

export const inputClass =
  'mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm shadow-sm ' +
  'focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500'

export const buttonClass =
  'rounded-lg bg-brand-600 px-3 py-2 text-sm font-medium text-white hover:bg-brand-700 ' +
  'disabled:cursor-not-allowed disabled:opacity-50'

export const secondaryButtonClass =
  'rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 ' +
  'hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50'
