import type { ApiErrorBody } from './types'

/** Carries the server's structured error so components can show the code and the message. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined

  constructor(body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = body.status
    this.code = body.code
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      ...(init?.body ? { 'content-type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    // The API always answers with the {status, error, message} shape, but a proxy or a crash could
    // still produce something else — fall back rather than throwing a parse error over the real one.
    let body: ApiErrorBody
    try {
      body = (await response.json()) as ApiErrorBody
    } catch {
      body = {
        status: response.status,
        error: response.statusText,
        message: `Request failed with status ${response.status}.`,
      }
    }
    throw new ApiError(body)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

/**
 * Queue names and message ids both routinely contain characters that must be escaped:
 * `DEV.QUEUE.1` is an ordinary MQ queue name, and ActiveMQ message ids look like
 * `ID:host-61616-123-0:1:2:3`.
 */
export function queueQuery(queueName: string, extra: Record<string, string | number> = {}): string {
  const params = new URLSearchParams({ queueName })
  for (const [key, value] of Object.entries(extra)) {
    params.set(key, String(value))
  }
  return params.toString()
}
