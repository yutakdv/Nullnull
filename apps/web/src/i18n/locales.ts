// Supported locales and their P0 availability.
//
// Product decision (CLAUDE.md, FCR-001): Korean and English are really
// selectable and restorable. Japanese and Chinese are disabled `준비 중` —
// they can receive focus but must never be selected, saved, or trigger a
// request.

export const LOCALES = ['ko-KR', 'en-US', 'ja-JP', 'zh-CN'] as const;

export type Locale = (typeof LOCALES)[number];

/** Locales a user can actually pick and persist in P0. */
export const SUPPORTED_LOCALES = ['ko-KR', 'en-US'] as const satisfies readonly Locale[];

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: SupportedLocale = 'ko-KR';

export function isSupportedLocale(value: string): value is SupportedLocale {
  return (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/**
 * Native names are shown as-is in every locale, so a Korean speaker still
 * recognises 日本語 and an English speaker still recognises 한국어.
 */
export const LOCALE_NATIVE_NAME: Record<Locale, string> = {
  'ko-KR': '한국어',
  'en-US': 'English',
  'ja-JP': '日本語',
  'zh-CN': '中文',
};

/** Maps a browser language tag onto a supported locale, or null. */
export function matchSupportedLocale(
  languages: readonly string[],
): SupportedLocale | null {
  for (const tag of languages) {
    const lower = tag.toLowerCase();
    if (lower === 'ko' || lower.startsWith('ko-')) return 'ko-KR';
    if (lower === 'en' || lower.startsWith('en-')) return 'en-US';
  }
  return null;
}
