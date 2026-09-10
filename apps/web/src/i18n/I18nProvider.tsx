import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import {
  DEFAULT_LOCALE,
  isSupportedLocale,
  matchSupportedLocale,
  type SupportedLocale,
} from './locales.js';
import { messages, type MessageKey } from './messages.js';

// The locale lives here as a local draft. getCurrentOwner supplies the stored
// value once bootstrap lands and updatePreferences writes it back (BA-011);
// until then the draft survives reloads through localStorage so a chosen
// language is genuinely restored rather than re-detected.
//
// Only ko-KR and en-US ever reach this state. Japanese and Chinese are shown
// as disabled `준비 중` and are never selected, stored, or sent (CLAUDE.md P0).

const STORAGE_KEY = 'nullnull.locale';

/** Values substitutable into a message placeholder. */
export type MessageValues = Record<string, string | number>;

interface I18nValue {
  locale: SupportedLocale;
  /** Persists the choice locally. Sends nothing until BA-011 opens. */
  setLocale: (locale: SupportedLocale) => void;
  /**
   * Looks up a message, substituting `{name}` placeholders.
   *
   * Counts are formatted for the locale rather than interpolated raw, so a
   * four-digit total does not appear unseparated in one language and separated
   * in another.
   */
  t: (key: MessageKey, values?: MessageValues) => string;
}

/**
 * Replaces `{name}` with the supplied value.
 *
 * A placeholder with no value is left as written rather than replaced with
 * `undefined`: a visible `{count}` is a bug report, while "undefined" reads as
 * real copy to a user and can ship unnoticed.
 */
function interpolate(
  template: string,
  values: MessageValues | undefined,
  locale: SupportedLocale,
): string {
  if (!values) return template;
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => {
    const value = values[name];
    if (value === undefined) return whole;
    return typeof value === 'number'
      ? new Intl.NumberFormat(locale).format(value)
      : value;
  });
}

const I18nContext = createContext<I18nValue | null>(null);

function initialLocale(): SupportedLocale {
  let stored: string | null = null;
  try {
    stored = localStorage.getItem(STORAGE_KEY);
  } catch {
    // Blocked storage: fall through to language detection.
  }
  if (stored && isSupportedLocale(stored)) return stored;
  return matchSupportedLocale(navigator.languages) ?? DEFAULT_LOCALE;
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState(initialLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: SupportedLocale) => {
    setLocaleState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Blocked storage: the choice still applies for this session.
    }
  }, []);

  const value = useMemo<I18nValue>(
    () => ({
      locale,
      setLocale,
      t: (key, values) => interpolate(messages[locale][key], values, locale),
    }),
    [locale, setLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
