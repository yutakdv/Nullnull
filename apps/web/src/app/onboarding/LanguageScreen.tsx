import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { SupportedLocale } from '../../i18n/locales.js';
import type { MessageKey } from '../../i18n/messages.js';
import { BottomCta } from '../../shared/ui/index.js';
import { useUpdatePreferences } from '../../shared/api/index.js';
import styles from './LanguageScreen.module.css';

// Figma: A-2 language `388:277` (KO selected), `643:4088` (EN selected).
// FR-ONB-02: Korean and English are selectable and restorable; Japanese and
// Chinese are disabled `준비 중` and never selected, stored, or requested.
//
// The heading is bilingual in both locales on purpose — it has to be readable
// before the user has chosen a language.

interface Option {
  id: string;
  locale: SupportedLocale | null;
  nameKey: MessageKey;
  subKey: MessageKey;
  /** The tag for the name itself, so a screen reader says 日本語 in Japanese. */
  lang: string;
}

const OPTIONS: Option[] = [
  {
    id: 'ko',
    locale: 'ko-KR',
    nameKey: 'language.ko.name',
    subKey: 'language.ko.sub',
    lang: 'ko',
  },
  {
    id: 'en',
    locale: 'en-US',
    nameKey: 'language.en.name',
    subKey: 'language.en.sub',
    lang: 'en',
  },
  {
    id: 'ja',
    locale: null,
    nameKey: 'language.ja.name',
    subKey: 'language.ja.sub',
    lang: 'ja',
  },
  {
    id: 'zh',
    locale: null,
    nameKey: 'language.zh.name',
    subKey: 'language.zh.sub',
    lang: 'zh',
  },
];

export function LanguageScreen() {
  const { locale, setLocale, t } = useI18n();
  const navigate = useNavigate();
  const updatePreferences = useUpdatePreferences();

  function choose(next: SupportedLocale) {
    setLocale(next);
    // Best effort: the local draft is already applied, and BA-011 is not open
    // yet. A rejection must not block onboarding, but it is not swallowed
    // either — the mutation's error state stays readable to this screen.
    updatePreferences.mutate({ locale: next });
  }

  return (
    <section className={styles.screen} aria-labelledby="language-heading">
      <div className={styles.body}>
        <div className={styles.head}>
          <h1 className={styles.title} id="language-heading">
            <span lang="en">{t('language.title.en')}</span>
            <br />
            <span lang="ko">{t('language.title.ko')}</span>
          </h1>
          <p className={styles.description}>{t('language.description')}</p>
        </div>

        <ul className={styles.options}>
          {OPTIONS.map((option) => {
            const selected = option.locale !== null && option.locale === locale;
            return (
              <li key={option.id}>
                <button
                  type="button"
                  lang={option.lang}
                  className={`${styles.option} ${selected ? styles.selected : ''}`}
                  // Japanese and Chinese are shown so the user knows they are
                  // planned, but they are inert: no selection, no request.
                  disabled={option.locale === null}
                  aria-current={selected ? 'true' : undefined}
                  onClick={
                    option.locale
                      ? () => {
                          choose(option.locale as SupportedLocale);
                        }
                      : undefined
                  }
                >
                  <span className={styles.names}>
                    <span className={styles.name}>{t(option.nameKey)}</span>
                    <span
                      className={styles.sub}
                      lang={option.locale === null ? undefined : 'en'}
                    >
                      {t(option.subKey)}
                    </span>
                  </span>
                  {selected ? (
                    <span className={styles.check} aria-hidden="true">
                      ✓
                    </span>
                  ) : null}
                  {selected ? (
                    <span className={styles.visuallyHidden}>
                      {t('language.selected')}
                    </span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <BottomCta
        label={t('language.next')}
        onClick={() => {
          void navigate('/intro');
        }}
      />
    </section>
  );
}
