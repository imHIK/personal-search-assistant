import { ApiError, NetworkError } from '@/api/http'

/**
 * Backend errors, rewritten into something a user can act on.
 *
 * The server has no exception mappers, so failures arrive as raw exception messages or a bare 500.
 * Showing those verbatim is useless ("SRCFG00040: The config property … considered to be null").
 * Each rule below maps a recognisable signature to a cause and a fix; the original text is always
 * kept in `raw` and shown under Technical details, so nothing is hidden — just de-emphasised.
 */

export interface FriendlyError {
  /** Short headline. */
  title: string
  /** What went wrong and what to do, in one or two sentences. */
  detail: string
  /** The original server message, for the Technical details disclosure. */
  raw?: string
}

interface Rule {
  match: RegExp
  title: string
  detail: string
}

const rules: Rule[] = [
  {
    match: /no such file|not a directory|does not exist|NoSuchFileException/i,
    title: "That folder couldn't be opened",
    detail: 'Check the path exists and is spelled correctly. It must be a folder, not a file.',
  },
  {
    match: /permission denied|AccessDenied|not readable/i,
    title: 'No permission to read that',
    detail:
      'This app can only index folders your user account can read. On macOS you may also need to grant Full Disk Access to the terminal running the server.',
  },
  {
    match: /invalid_grant|invalid_client|unauthorized|401|token.*(expired|revoked|invalid)/i,
    title: 'The sign-in was rejected',
    detail:
      'The refresh token is expired, revoked, or belongs to a different OAuth client. Generate a new one and update the account.',
  },
  {
    match: /403|insufficient.*(scope|permission)|accessNotConfigured/i,
    title: 'That account is missing a permission',
    detail:
      'The token works, but not for this data. Re-run the consent flow including the read-only scope for this service, then paste the new refresh token.',
  },
  {
    match: /no connection|connection not found|requires a connection/i,
    title: 'No account is connected',
    detail: 'Add an account for this service first, then point this source at it.',
  },
  {
    match: /model-path|onnx|embedding.*(model|provider)|no embedding/i,
    title: 'The embedding model is not set up',
    detail:
      'Indexing and semantic search need a model. Either export the ONNX model and set app.embedding.onnx.model-path, or set app.embedding.provider=local-hashing for local development.',
  },
  {
    match: /llm|groq|api.?key|StubLlmProvider|completion/i,
    title: 'The answer service is unavailable',
    detail:
      'Written answers need an LLM provider. Set GROQ_API_KEY (or another OpenAI-compatible endpoint) and restart the server. Search itself still works without it.',
  },
  {
    match: /opensearch|connection refused.*9200|index_not_found/i,
    title: 'The search index is unreachable',
    detail: 'OpenSearch is not responding. Start it with docker compose up -d.',
  },
  {
    match: /mongo|connection refused.*27017|MongoSocket/i,
    title: 'The database is unreachable',
    detail: 'MongoDB is not responding. Start it with docker compose up -d.',
  },
  {
    match: /still (used|bound)|in use by|has knowledge/i,
    title: 'Still in use',
    detail: 'One or more sources use this account. Repoint or remove them first.',
  },
  {
    match: /immutable|cannot change.*type/i,
    title: "That can't be changed",
    detail: 'The connector type is fixed once a source is created. Add a new source instead.',
  },
  {
    match: /deleted/i,
    title: 'This source has been removed',
    detail: 'Removed sources are read-only.',
  },
]

export function friendlyError(error: unknown): FriendlyError {
  if (error instanceof NetworkError) {
    return {
      title: "Can't reach the server",
      detail:
        'The backend is not responding. Start it with ./gradlew quarkusDev, and make sure MongoDB and OpenSearch are running via docker compose up -d.',
    }
  }

  const raw = error instanceof Error ? error.message : String(error)

  if (error instanceof ApiError) {
    if (error.isNotFound) {
      return { title: 'Not found', detail: 'It may have been removed already.', raw }
    }
    const matched = rules.find((rule) => rule.match.test(raw))
    if (matched) return { title: matched.title, detail: matched.detail, raw }

    if (error.isConflict) {
      return { title: "That isn't possible right now", detail: raw, raw }
    }
    if (error.isBadRequest) {
      return { title: "That didn't work", detail: raw, raw }
    }
    return {
      title: 'Something went wrong on the server',
      detail: 'The server hit an unexpected error. The details below may explain why.',
      raw,
    }
  }

  const matched = rules.find((rule) => rule.match.test(raw))
  if (matched) return { title: matched.title, detail: matched.detail, raw }

  return { title: 'Something went wrong', detail: raw || 'No further detail was given.', raw }
}

/**
 * Same translation for the `lastError` string carried on a Knowledge or Connection — those never
 * come through an HTTP error, so they need their own entry point.
 */
export function friendlyLastError(lastError: string): FriendlyError {
  const matched = rules.find((rule) => rule.match.test(lastError))
  if (matched) return { title: matched.title, detail: matched.detail, raw: lastError }
  return { title: 'Something went wrong', detail: lastError, raw: lastError }
}
