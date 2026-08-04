/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Base path for API calls. Empty (the default) means same-origin: the dev server proxies to
   * Quarkus, and the production bundle is served by Quarkus itself. Set this only when running
   * the frontend against a backend on a different origin — which also needs CORS enabled there.
   */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
