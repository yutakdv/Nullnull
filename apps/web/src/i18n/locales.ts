// P0 locale policy (CLAUDE.md, FCR-001): Korean and English are selectable
// and restorable. Japanese and Chinese are `준비 중` and never selected,
// saved, or requested. The language screen (FE-101) owns their display names.

export const SUPPORTED_LOCALES = ['ko-KR', 'en-US'] as const;

export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

export const DEFAULT_LOCALE: SupportedLocale = 'ko-KR';

export function isSupportedLocale(value: string): value is SupportedLocale {
  return (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/** Maps browser language tags onto a supported locale, or null. */
export function matchSupportedLocale(
  languages: readonly string[],
): SupportedLocale | null {
  if (languages.some((l) => /^ko(-|$)/i.test(l))) return 'ko-KR';
  if (languages.some((l) => /^en(-|$)/i.test(l))) return 'en-US';
  return null;
}
