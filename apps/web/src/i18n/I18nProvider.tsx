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
import { PARTICLES } from './particles.js';

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
 * Replaces `{name}` with the supplied value, and `{name:을}` with the value
 * followed by the particle its sound takes (#279 하6).
 *
 * A placeholder with no value is left as written rather than replaced with
 * `undefined`: a visible `{count}` is a bug report, while "undefined" reads as
 * real copy to a user and can ship unnoticed. A particle on a missing value is
 * left written too, for the same reason.
 *
 * The particle is decided HERE rather than at the call site because it depends
 * on the value that actually lands in the slot — the same template renders
 * 경복궁을 and 제주를. Deciding it in the screen would mean every caller
 * remembering to, and the ones that forgot are what this is fixing.
 *
 * Korean only by construction: the en templates carry no markers, so the
 * replacement never fires for them.
 */
function interpolate(
  template: string,
  values: MessageValues | undefined,
  locale: SupportedLocale,
): string {
  if (!values) return template;
  // `[^}]` for the marker, not `\w`: JavaScript's `\w` is ASCII-only and would
  // not match 을, so `{name:을}` would fall through as a literal.
  return template.replace(
    /\{(\w+)(?::([^}]+))?\}/g,
    (whole, name: string, particle?: string) => {
      const value = values[name];
      if (value === undefined) return whole;
      const text =
        typeof value === 'number' ? new Intl.NumberFormat(locale).format(value) : value;
      if (particle === undefined) return text;
      const choose = PARTICLES[particle];
      // An unknown marker is left as written, like an unknown placeholder: a
      // visible `{name:xx}` is a bug report, and silently dropping it would ship
      // a sentence missing its particle.
      return choose ? `${text}${choose(text)}` : whole;
    },
  );
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
