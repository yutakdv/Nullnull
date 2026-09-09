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

interface I18nValue {
  locale: SupportedLocale;
  /** Persists the choice locally. Sends nothing until BA-011 opens. */
  setLocale: (locale: SupportedLocale) => void;
  t: (key: MessageKey) => string;
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
    () => ({ locale, setLocale, t: (key) => messages[locale][key] }),
    [locale, setLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
