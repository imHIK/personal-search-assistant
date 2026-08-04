/**
 * Capabilities the backend does not have yet. Each is a one-line flip once it lands, rather than
 * a hunt for the places a feature would need to be re-added. See ROADMAP.md and docs/limitations.md.
 */
export const features = {
  /**
   * `Knowledge.config.webhookSettings` exists and `PATCH` accepts `webhookEnabled`/`webhookSecret`,
   * but there is no webhook endpoint to call — so the fields are hidden rather than offered.
   */
  webhooks: false,

  /** Google consent redirect + code exchange. Until then, accounts take a pasted refresh token. */
  oauthRedirect: false,

  /** Only per-entity reindex exists (`POST /api/index/entities/{id}/reindex`). */
  reindexWholeSource: false,

  /** The agent is single-shot: no conversation memory, no follow-up questions. */
  conversationalSearch: false,

  /** Reranking is a NoopReranker passthrough, so scores are raw RRF and not meaningfully calibrated. */
  calibratedScores: false,

  /** No auth of any kind server-side. Single user, single machine. */
  authentication: false,
} as const

export type Feature = keyof typeof features
