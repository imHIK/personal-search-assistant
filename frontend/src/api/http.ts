
const BASE = import.meta.env.VITE_API_BASE ?? ''

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
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
 * Quarkus answers explicitly thrown exceptions with JSON; an unmapped 500 is an HTML or plain-text
 * error page.
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

  const detail = quarkusDetail(text)
  if (detail) return detail

  return text.length > 400 ? `${response.status} ${response.statusText}` : text.trim()
}

/**
 * Quarkus' plain-text 500 page is ~3 KB of stack trace. The line after `Details:` reads
 * `Error id <id>, <package>.<Exception>: <message>`; the id and the package are dropped.
 */
function quarkusDetail(text: string): string | undefined {
  const line = /^[ \t]*Details:[ \t]*\r?\n[ \t]*(.+)$/m.exec(text)?.[1]?.trim()
  if (!line) return undefined
  return line
    .replace(/^Error id \S+?,\s*/, '')
    .replace(/^(?:[a-z0-9_]+\.)+([A-Z]\w*)/, '$1')
    .slice(0, 400)
}

export function query(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    search.set(key, String(value))
  }
  const rendered = search.toString()
  return rendered ? `?${rendered}` : ''
}
