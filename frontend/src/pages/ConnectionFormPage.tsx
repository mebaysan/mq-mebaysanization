import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'

import { ApiError } from '../api/client'
import { useConnection, useSaveConnection, useTestDraft } from '../api/connections'
import type { ConnectionProfileRequest, Provider } from '../api/types'
import {
  ErrorBanner,
  Field,
  Skeleton,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from '../components/Primitives'
import { useToast } from '../components/ToastProvider'
import { PROVIDERS, PROVIDER_FIELDS } from '../features/connections/ProviderFields'

const EMPTY: ConnectionProfileRequest = {
  name: '',
  provider: 'ACTIVE_MQ',
  host: 'localhost',
  port: 61616,
  username: '',
  password: '',
  brokerUrlOverride: '',
  bootstrapServers: '',
  queueManagerName: '',
  channel: '',
}

export default function ConnectionFormPage() {
  const params = useParams()
  const navigate = useNavigate()
  const toast = useToast()

  const id = params.id ? Number(params.id) : undefined
  const isEdit = id !== undefined

  const existing = useConnection(id)
  const save = useSaveConnection(id)
  const testDraft = useTestDraft()

  const [form, setForm] = useState<ConnectionProfileRequest>(EMPTY)
  const [loaded, setLoaded] = useState(!isEdit)

  useEffect(() => {
    if (!isEdit || !existing.data || loaded) return
    const profile = existing.data
    setForm({
      id: profile.id,
      name: profile.name,
      provider: profile.provider,
      host: profile.host ?? '',
      port: profile.port,
      username: profile.username ?? '',
      // Deliberately null, not '': the current password is never sent to this page, and null means
      // "leave the stored one alone". An empty string would clear it on save.
      password: null,
      brokerUrlOverride: profile.brokerUrlOverride ?? '',
      bootstrapServers: profile.bootstrapServers ?? '',
      queueManagerName: profile.queueManagerName ?? '',
      channel: profile.channel ?? '',
    })
    setLoaded(true)
  }, [isEdit, existing.data, loaded])

  const fields = PROVIDER_FIELDS[form.provider]

  const update = <K extends keyof ConnectionProfileRequest>(
    key: K,
    value: ConnectionProfileRequest[K],
  ) => setForm((current) => ({ ...current, [key]: value }))

  const changeProvider = (provider: Provider) => {
    const next = PROVIDER_FIELDS[provider]
    setForm((current) => ({
      ...current,
      provider,
      // Clear every field the new provider does not use, so a switched provider never submits a
      // combination the server is bound to reject.
      host: next.hostAndPort ? current.host || 'localhost' : '',
      port: next.hostAndPort ? next.defaultPort : null,
      bootstrapServers: next.bootstrapServers ? current.bootstrapServers : '',
      brokerUrlOverride: next.brokerUrlOverride ? current.brokerUrlOverride : '',
      queueManagerName: next.queueManagerAndChannel ? current.queueManagerName : '',
      channel: next.queueManagerAndChannel ? current.channel : '',
    }))
  }

  /** Blank optional fields are sent as null so the server's cross-field validation sees them as absent. */
  const toRequest = (): ConnectionProfileRequest => ({
    ...form,
    host: form.host?.trim() || null,
    username: form.username?.trim() || null,
    brokerUrlOverride: form.brokerUrlOverride?.trim() || null,
    bootstrapServers: form.bootstrapServers?.trim() || null,
    queueManagerName: form.queueManagerName?.trim() || null,
    channel: form.channel?.trim() || null,
    password: form.password,
  })

  const runTest = () => {
    testDraft.mutate(toRequest(), {
      onSuccess: (result) =>
        result.success
          ? toast.success(`Connected in ${result.durationMs} ms.`)
          : toast.error(result.message),
      onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
    })
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    save.mutate(toRequest(), {
      onSuccess: (saved) => {
        toast.success(`Saved "${saved.name}".`)
        void navigate('/connections')
      },
      onError: (err) => toast.error(err instanceof ApiError ? err.message : String(err)),
    })
  }

  if (isEdit && existing.isPending) {
    return <Skeleton rows={6} />
  }

  return (
    <div className="max-w-2xl space-y-6">
      <div>
        <Link to="/connections" className="text-sm text-brand-700 hover:underline">
          ← Back to connections
        </Link>
        <h1 className="mt-2 text-xl font-semibold text-slate-900">
          {isEdit ? `Edit ${existing.data?.name ?? 'connection'}` : 'New connection'}
        </h1>
      </div>

      {isEdit && existing.isError && (
        <ErrorBanner
          message={
            existing.error instanceof ApiError ? existing.error.message : String(existing.error)
          }
        />
      )}

      <form onSubmit={submit} className="space-y-5 rounded-xl border border-slate-200 bg-white p-5">
        <Field label="Name">
          <input
            className={inputClass}
            value={form.name}
            onChange={(event) => update('name', event.target.value)}
            placeholder="Prod ActiveMQ"
            required
          />
        </Field>

        <fieldset>
          <legend className="text-sm font-medium text-slate-700">Provider</legend>
          <div className="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            {PROVIDERS.map((value) => (
              <label
                key={value}
                className={`cursor-pointer rounded-lg border p-3 text-sm ${
                  form.provider === value
                    ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500'
                    : 'border-slate-300 hover:bg-slate-50'
                }`}
              >
                <input
                  type="radio"
                  name="provider"
                  className="sr-only"
                  checked={form.provider === value}
                  onChange={() => changeProvider(value)}
                />
                <span className="block font-medium text-slate-900">
                  {PROVIDER_FIELDS[value].label}
                </span>
                <span className="mt-1 block text-xs text-slate-500">
                  {PROVIDER_FIELDS[value].blurb}
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        {fields.hostAndPort && (
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="sm:col-span-2">
              <Field label="Host">
                <input
                  className={inputClass}
                  value={form.host ?? ''}
                  onChange={(event) => update('host', event.target.value)}
                  placeholder="localhost"
                />
              </Field>
            </div>
            <Field label="Port">
              <input
                className={inputClass}
                type="number"
                value={form.port ?? ''}
                onChange={(event) =>
                  update('port', event.target.value === '' ? null : Number(event.target.value))
                }
              />
            </Field>
          </div>
        )}

        {fields.bootstrapServers && (
          <Field
            label="Bootstrap servers"
            hint="Comma-separated host:port seed brokers. Kafka discovers the rest of the cluster from these, so one is enough — list more so a single broker being down does not stop you connecting."
          >
            <input
              className={inputClass}
              value={form.bootstrapServers ?? ''}
              onChange={(event) => update('bootstrapServers', event.target.value)}
              placeholder="broker1:9092,broker2:9092"
            />
          </Field>
        )}

        {fields.queueManagerAndChannel && (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Queue manager" hint="Required for IBM MQ.">
              <input
                className={inputClass}
                value={form.queueManagerName ?? ''}
                onChange={(event) => update('queueManagerName', event.target.value)}
                placeholder="QM1"
              />
            </Field>
            <Field label="Channel" hint="Server-connection channel, e.g. DEV.APP.SVRCONN.">
              <input
                className={inputClass}
                value={form.channel ?? ''}
                onChange={(event) => update('channel', event.target.value)}
                placeholder="DEV.APP.SVRCONN"
              />
            </Field>
          </div>
        )}

        {fields.brokerUrlOverride && (
          <Field
            label="Broker URL override"
            hint="Optional. Used verbatim instead of host and port — the way to express something the fields cannot, such as failover://(tcp://a,tcp://b)."
          >
            <input
              className={inputClass}
              value={form.brokerUrlOverride ?? ''}
              onChange={(event) => update('brokerUrlOverride', event.target.value)}
              placeholder="tcp://broker:61616"
            />
          </Field>
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Username" hint="Leave blank if the broker allows anonymous access.">
            <input
              className={inputClass}
              value={form.username ?? ''}
              onChange={(event) => update('username', event.target.value)}
              autoComplete="off"
            />
          </Field>
          <Field
            label="Password"
            hint={
              isEdit
                ? 'Leave blank to keep the stored password. Clearing it removes the stored password.'
                : undefined
            }
          >
            <input
              className={inputClass}
              type="password"
              value={form.password ?? ''}
              onChange={(event) => update('password', event.target.value)}
              placeholder={isEdit && existing.data?.hasPassword ? '•••••••• (unchanged)' : ''}
              autoComplete="new-password"
            />
          </Field>
        </div>

        {fields.credentialsNote && (
          <p className="-mt-2 text-xs text-slate-500">{fields.credentialsNote}</p>
        )}

        {isEdit && existing.data && !existing.data.credentialsReadable && (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            The stored password cannot be decrypted with the current encryption key. Enter it again to
            repair this connection.
          </div>
        )}

        <div className="flex items-center justify-between border-t border-slate-100 pt-4">
          <button
            type="button"
            className={secondaryButtonClass}
            onClick={runTest}
            disabled={testDraft.isPending}
          >
            {testDraft.isPending ? 'Testing…' : 'Test connection'}
          </button>
          <div className="flex gap-2">
            <Link to="/connections" className={secondaryButtonClass}>
              Cancel
            </Link>
            <button type="submit" className={buttonClass} disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save connection'}
            </button>
          </div>
        </div>
      </form>
    </div>
  )
}
