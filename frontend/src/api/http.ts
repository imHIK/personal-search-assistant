/**
 * The single fetch seam. Everything that talks to the backend goes through `http()`, so the
 * quirks of this particular API are handled in exactly one place:
 *
 *  - Several endpoints return **204 with no body** (pause/resume/delete/reindex). Parsing those
 *    as JSON throws, so a no-content response resolves to `null`.
 *  - There are **no exception mappers** server-side, so a non-4xx failure surfaces as a raw 500
 *    with a Quarkus HTML or JSON error body. `ApiError` normalises both into a message.
 */

/** Base path for the API. Same-origin by default — dev proxies it, prod serves it from Quarkus. */
const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Raw response body, kept for the "technical details" disclosure. */
    readonly body?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  get isNotFound() {
    return this.status === 404
  }

  get isConflict() {
    return this.status === 409
  }

  get isBadRequest() {
    return this.status === 400
  }
}

/** Thrown when the request never reached the server at all (backend down, DNS, offline). */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('Could not reach the server')
    this.name = 'NetworkError'
    this.cause = cause
  }
}

interface Options {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

export async function http<T>(path: string, options: Options = {}): Promise<T> {
  const { method = 'GET', body, signal } = options

  let response: Response
  try {
    response = await fetch(BASE + path, {
      method,
      signal,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new NetworkError(cause)
  }

  if (!response.ok) {
    throw new ApiError(response.status, await extractMessage(response), undefined)
  }

  // 204, or a 200 that genuinely carries nothing.
  if (response.status === 204 || response.headers.get('content-length') === '0') {
    return null as T
  }
  const text = await response.text()
  if (!text) return null as T
  return JSON.parse(text) as T
}

/**
 * Pull something human out of an error response. Quarkus returns JSON for the exceptions the
 * resources throw explicitly, but an unmapped 500 comes back as an HTML error page — in that case
 * the status line is more useful than a wall of markup.
 */
async function extractMessage(response: Response): Promise<string> {
  const text = await response.text().catch(() => '')
  if (!text) return `${response.status} ${response.statusText}`

  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('application/json')) {
    try {
      const parsed = JSON.parse(text) as Record<string, unknown>
      const message = parsed.details ?? parsed.message ?? parsed.error ?? parsed.title
      if (typeof message === 'string' && message.trim()) return message
    } catch {
      // fall through to the raw text
    }
  }
  if (contentType.includes('text/html')) {
    // Quarkus' dev error page — its <title> is the exception summary.
    const title = /<title>([^<]+)<\/title>/i.exec(text)?.[1]
    return title?.trim() || `${response.status} ${response.statusText}`
  }
  return text.length > 400 ? `${response.status} ${response.statusText}` : text.trim()
}

/** Build a query string, omitting undefined/null/empty values. */
export function query(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    search.set(key, String(value))
  }
  const rendered = search.toString()
  return rendered ? `?${rendered}` : ''
}
