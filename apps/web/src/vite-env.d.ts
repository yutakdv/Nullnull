/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Set to 'on' to start the MSW worker in dev. Ignored in a production build. */
  readonly VITE_API_MOCKING?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
