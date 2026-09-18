import { useI18n } from '../../i18n/I18nProvider.js';
import wizard from './TripWizardScreen.module.css';
import styles from './InputMethodStep.module.css';

// Figma: S02-4C 입력 방법 선택 `400:1201` (FR-TRC-05, FE-103).
//
// Step 4 of the branch 거의 다 세우고 왔어요 takes: paste what you already
// wrote, or enter it day by day. Two options, no CTA — choosing IS the action,
// which is why the frame draws each row with an arrow rather than a checkmark
// plus a 다음 button.
//
// A STEP of the wizard, not a route of its own. That distinction is the whole
// reason this screen can exist now: `wizard.ts` used to send MOSTLY_PLANNED
// straight to createTrip because routing to the standalone `/start/import`
// would drop the dates and interests already collected — ImportPasteScreen
// builds its own draft from EMPTY_DRAFT. Held here, the draft survives the
// choice.
//
// The icons in the frame (`437:3043` check, `437:3061` chevron) are decorative
// tiles, not state: both options are always available and neither is selected
// when the screen opens. They are rendered as the arrow the row already
// carries rather than exported as assets, because a check mark next to an
// unchosen option reads as "already done".

export interface InputMethodStepProps {
  /** Paste an itinerary the traveller already wrote (S02-4C-A `401:1221`). */
  onPaste: () => void;
  /** Build it day by day in the wizard (S02-4C-C `438:3199`). */
  onManual: () => void;
}

export function InputMethodStep({ onPaste, onManual }: InputMethodStepProps) {
  const { t } = useI18n();

  return (
    <>
      <div className={wizard.head}>
        <h1 className={wizard.title}>
          {t('method.title1')}
          <br />
          {t('method.title2')}
        </h1>
        <p className={wizard.lead}>{t('method.lead')}</p>
      </div>

      <ul className={styles.options}>
        <li>
          <button className={styles.option} onClick={onPaste} type="button">
            <span className={styles.optionText}>
              <span className={styles.optionName}>{t('method.paste')}</span>
              <span className={styles.optionHint}>{t('method.pasteHint')}</span>
            </span>
            <span aria-hidden="true" className={styles.arrow}>
              →
            </span>
          </button>
        </li>
        <li>
          <button className={styles.option} onClick={onManual} type="button">
            <span className={styles.optionText}>
              <span className={styles.optionName}>{t('method.manual')}</span>
              <span className={styles.optionHint}>{t('method.manualHint')}</span>
            </span>
            <span aria-hidden="true" className={styles.arrow}>
              →
            </span>
          </button>
        </li>
      </ul>
    </>
  );
}
