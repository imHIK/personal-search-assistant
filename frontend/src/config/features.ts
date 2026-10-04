export const features = {
  /** PATCH accepts webhook settings, but no endpoint receives webhooks. */
  webhooks: false,

  oauthRedirect: false,

  reindexWholeSource: false,

  conversationalSearch: false,

  calibratedScores: false,

  authentication: false,
} as const

export type Feature = keyof typeof features
