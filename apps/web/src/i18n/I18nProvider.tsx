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

// Locale lives here as client state until the session bootstrap lands
// (FE-101). At that point the persisted value comes from getCurrentOwner and
// changes go through updatePreferences; this provider keeps the same shape so
// screens do not change.

const STORAGE_KEY = 'nullnull.locale';

interface I18nValue {
  locale: SupportedLocale;
  setLocale: (next: SupportedLocale) => void;
  t: (key: MessageKey) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

function readStoredLocale(): SupportedLocale | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored && isSupportedLocale(stored) ? stored : null;
  } catch {
    // Private mode or blocked storage: fall through to language detection.
    return null;
  }
}

function initialLocale(): SupportedLocale {
  return (
    readStoredLocale() ??
    matchSupportedLocale(typeof navigator === 'undefined' ? [] : navigator.languages) ??
    DEFAULT_LOCALE
  );
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<SupportedLocale>(initialLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: SupportedLocale) => {
    setLocaleState(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Persistence is best-effort; the in-memory choice still applies.
    }
  }, []);

  const value = useMemo<I18nValue>(
    () => ({
      locale,
      setLocale,
      t: (key) => messages[locale][key],
    }),
    [locale, setLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) {
    throw new Error('useI18n must be used inside I18nProvider');
  }
  return value;
}
