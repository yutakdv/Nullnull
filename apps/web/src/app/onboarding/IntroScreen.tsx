import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { BottomCta, IconHeart, IconLocation, IconPlus } from '../../shared/ui/index.js';
import { useUpdatePreferences } from '../../shared/api/index.js';
import styles from './IntroScreen.module.css';

// Figma: A-3 intro `388:321`.
//
// FR-ONB-03: continue or skip, and a completed intro must not be shown again.
// Both paths mark onboarding complete and land on the same place, so "skip"
// means "skip reading", not "skip recording". The icons are decorative — the
// adjacent text is the label.

const POINTS: { Icon: typeof IconHeart; titleKey: MessageKey; bodyKey: MessageKey }[] = [
  { Icon: IconHeart, titleKey: 'intro.point1.title', bodyKey: 'intro.point1.body' },
  { Icon: IconPlus, titleKey: 'intro.point2.title', bodyKey: 'intro.point2.body' },
  { Icon: IconLocation, titleKey: 'intro.point3.title', bodyKey: 'intro.point3.body' },
];

export function IntroScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const updatePreferences = useUpdatePreferences();

  function start() {
    // Best effort until BA-011 opens: onboarding must not stall on a request
    // the server does not answer yet.
    updatePreferences.mutate({ onboardingCompleted: true });
    void navigate('/feed', { replace: true });
  }

  return (
    <section className={styles.screen} aria-labelledby="intro-heading">
      <div className={styles.body}>
        <div className={styles.copy}>
          <h1 className={styles.title} id="intro-heading">
            {t('intro.title1')}
            <br />
            {t('intro.title2')}
          </h1>
          <p className={styles.lead}>
            {t('intro.body1')}
            <br />
            {t('intro.body2')}
          </p>
        </div>

        <ul className={styles.points}>
          {POINTS.map(({ Icon, titleKey, bodyKey }) => (
            <li className={styles.point} key={titleKey}>
              <span className={styles.badge}>
                <Icon size={20} />
              </span>
              <span className={styles.pointText}>
                <span className={styles.pointTitle}>{t(titleKey)}</span>
                <span className={styles.pointBody}>{t(bodyKey)}</span>
              </span>
            </li>
          ))}
        </ul>
      </div>

      <BottomCta
        label={t('intro.start')}
        onClick={start}
        secondary={<p className={styles.noLogin}>{t('intro.noLogin')}</p>}
      />
    </section>
  );
}
