import { Link } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import styles from './LiveScreen.module.css';

// Figma: S11 Live `418:2523` (FR-LIV-*, FE-401~403). NOT BUILT YET.
//
// This is a deliberate 준비 중 screen, not the Live feature. The real one
// needs queryLiveAreas and listLiveAreaPlaces, which BA-091 has not opened, and
// P0's rule is that a list must work without a map at all.
//
// Why it exists rather than the route rendering a PlaceholderScreen: 라이브 is
// one of four persistent tabs, reachable from the feed, a trip and the profile,
// and the placeholder rendered the app name above the untranslated literal
// "live". That reads as a broken build rather than an unfinished feature —
// TabBar's own comment says an unsupported tab that navigates nowhere is worse
// than an absent one, and this is the version of that tab that says what it is.
//
// What it must NOT do: show a crowd figure. There is no live data behind this
// screen, and a placeholder number would be the unsourced reading invariant 8
// and AGENTS.md rule 6 forbid. It links to the data guide instead, which
// explains what the states mean without claiming any.

export function LiveScreen() {
  const { t } = useI18n();
  return (
    <section aria-labelledby="live-heading" className={styles.screen}>
      <div className={styles.head}>
        <h1 className={styles.title} id="live-heading">
          {t('live.title')}
        </h1>
        {/* Inert text, not a control: there is nothing to press and no
            request to send while the capability is off. */}
        <span className={styles.badge}>{t('live.comingSoon')}</span>
      </div>

      <p className={styles.description}>{t('live.description')}</p>

      <p className={styles.meanwhile}>{t('live.meanwhile')}</p>
      <Link className={styles.link} to="/about-data">
        {t('live.dataGuide')}
        <span aria-hidden="true">›</span>
      </Link>
    </section>
  );
}
