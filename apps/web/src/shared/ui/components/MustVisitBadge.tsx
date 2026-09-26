import { useOptionalI18n } from '../../../i18n/I18nProvider.js';
import { IconPinVisitFilled } from '../icons/index.js';
import styles from './MustVisitBadge.module.css';

// Figma: `Data / Must Visit` (C40). Marks a MUST_VISIT constraint.
// The lock itself is independent of DATE/TIME/RESERVATION and is never
// released automatically (CLAUDE.md invariant 7); this only displays it.

/** Only for a render with no I18nProvider (a story); the app takes `mustVisit.badge`. */
const DEFAULT_LABEL = '꼭 가요';

export function MustVisitBadge({ label }: { label?: string }) {
  const i18n = useOptionalI18n();
  return (
    <span className={styles.badge}>
      <IconPinVisitFilled size={12} />
      {label ?? i18n?.t('mustVisit.badge') ?? DEFAULT_LABEL}
    </span>
  );
}
