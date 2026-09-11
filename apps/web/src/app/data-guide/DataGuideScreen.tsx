import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { NavBar, StateLabel, type SourceState } from '../../shared/ui/index.js';
import styles from './DataGuideScreen.module.css';

// Figma: S15 data-guide `423:2967`.
//
// FR-DAT-01: explain the six data states and what the app will and will not do.
// This screen calls no API — it is the place the product states its own rules,
// so everything here is copy plus the StateLabel component (C07).
//
// The state wording is deliberately NOT duplicated here. Figma's component
// description pins it ("문구 임의 변경 금지") and StateLabel owns the shape, so
// this screen passes the localized strings through rather than writing its
// own second set beside the long-form explanations.

const STATES: SourceState[] = [
  'LIVE',
  'FORECAST',
  'QUALITATIVE',
  'STALE',
  'UNAVAILABLE',
  'REPLAY',
];

const RULES = [1, 2, 3, 4, 5] as const;

export function DataGuideScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();

  // StateLabel keeps Korean defaults so Storybook can mount it without a
  // provider; inside the app it takes the selected locale's words. Without
  // this the screen read "실시간 관측" beside an English explanation of it.
  const stateLabels = Object.fromEntries(
    STATES.map((state) => [state, t(`state.${state}` as MessageKey)]),
  ) as Partial<Record<SourceState, string>>;

  return (
    <section className={styles.screen} aria-labelledby="data-guide-heading">
      {/* Reached from the profile, and by deep link. Named destination rather
          than history.go(-1) (COMPONENT_CATALOG C49). */}
      <NavBar
        backLabel={t('nav.back')}
        onBack={() => {
          void navigate('/profile');
        }}
      />

      <h1 className={styles.title} id="data-guide-heading">
        {t('dataGuide.title1')}
        <br />
        {t('dataGuide.title2')}
      </h1>

      <h2 className={styles.sectionTitle} id="data-guide-states">
        {t('dataGuide.states.heading')}
      </h2>
      <ul className={styles.rows} aria-labelledby="data-guide-states">
        {STATES.map((state) => (
          <li className={styles.row} key={state}>
            <StateLabel labels={stateLabels} state={state} />
            <p className={styles.rowBody}>
              {t(`dataGuide.state.${state}` as MessageKey)}
            </p>
          </li>
        ))}
      </ul>

      <h2 className={styles.sectionTitle} id="data-guide-rules">
        {t('dataGuide.rules.heading')}
      </h2>
      <ul className={styles.rows} aria-labelledby="data-guide-rules">
        {RULES.map((n) => (
          <li className={styles.guideRow} key={n}>
            <span className={styles.guideTitle}>
              {t(`dataGuide.rule${n}.title` as MessageKey)}
            </span>
            <span className={styles.rowBody}>
              {t(`dataGuide.rule${n}.body` as MessageKey)}
            </span>
          </li>
        ))}
      </ul>

      {/* Required attribution for the KTO and Seoul sources (invariant 12). */}
      <p className={styles.attribution}>{t('dataGuide.attribution')}</p>
    </section>
  );
}
