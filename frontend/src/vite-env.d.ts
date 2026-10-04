/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Empty (the default) means same-origin. */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
