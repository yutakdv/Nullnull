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

// The locale lives here, and localStorage is what restores it: a chosen
// language survives a reload rather than being re-detected. The language
// screen also writes it to the owner record (updatePreferences, FE-101-T4),
// because place names are projected in the owner's locale (WEB-RT-1).
//
// Nothing reads the owner record's locale back into the UI. This comment used
// to say getCurrentOwner did, and no code does. The record starts at ko-KR, so
// it cannot tell a choice from the default: applying it at bootstrap would
// turn an English browser Korean on its first visit. It starts there only
// because the bootstrap names no locale — session.ts posts an empty body and
// ko-KR is the server's default for that — not because the contract forces it.
//
// When to trust it is not decided yet. The options: (a) apply it only once
// onboarding is complete and nothing is stored locally, (b) have the contract
// say whether the value was chosen, (c) never read it and keep localStorage
// alone (what the code does today), (d) send the detected locale when the
// session is created, so the record matches the screen from the start.
// (d) needs no contract change: `CreateDemoSessionRequest.locale` is already
// accepted.
//
// Only ko-KR and en-US ever reach this state. Japanese and Chinese are shown
// as disabled `준비 중` and are never selected, stored, or sent (CLAUDE.md P0).

const STORAGE_KEY = 'nullnull.locale';

/** Values substitutable into a message placeholder. */
export type MessageValues = Record<string, string | number>;

interface I18nValue {
  locale: SupportedLocale;
  /** Persists the choice locally and sends nothing; the language screen saves it. */
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

/**
 * The provider's value, or null outside one. For the shared data components
 * that must also render bare (a unit test; the Storybook stories run inside a
 * provider, .storybook/preview.tsx): inside the app they take
 * the chosen locale's words rather than their own Korean defaults, so a caller
 * that passes an incomplete label map cannot put Korean on an English screen.
 */
export function useOptionalI18n(): I18nValue | null {
  return useContext(I18nContext);
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
