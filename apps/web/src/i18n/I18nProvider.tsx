import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  DEFAULT_LOCALE,
  isSupportedLocale,
  matchSupportedLocale,
  type SupportedLocale,
} from './locales.js';
import { messages, type MessageKey } from './messages.js';

// Local draft of the locale until session bootstrap (FE-101) makes
// getCurrentOwner the source and updatePreferences the writer. The language
// screen adds the setter; the shell only reads.

const STORAGE_KEY = 'nullnull.locale';

interface I18nValue {
  locale: SupportedLocale;
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
  const [locale] = useState(initialLocale);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  return (
    <I18nContext.Provider value={{ locale, t: (key) => messages[locale][key] }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
