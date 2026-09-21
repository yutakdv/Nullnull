/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Browser-visible Kakao Maps JavaScript key; restrict it by registered domain. */
  readonly VITE_KAKAO_MAP_APP_KEY?: string;
  /** Set to 'on' to start the MSW worker in dev. Ignored in a production build. */
  readonly VITE_API_MOCKING?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
