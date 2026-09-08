import { IconPinVisitFilled } from '../icons/index.js';
import styles from './MustVisitBadge.module.css';

// Figma: `Data / Must Visit` (C40). Marks a MUST_VISIT constraint.
// The lock itself is independent of DATE/TIME/RESERVATION and is never
// released automatically (CLAUDE.md invariant 7); this only displays it.
export function MustVisitBadge({ label = '꼭 가요' }: { label?: string }) {
  return (
    <span className={styles.badge}>
      <IconPinVisitFilled size={12} />
      {label}
    </span>
  );
}
