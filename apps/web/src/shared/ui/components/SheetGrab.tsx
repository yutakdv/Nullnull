import styles from './SheetGrab.module.css';

// Figma: `Sheet / Grab` (C44). Decorative affordance at the top of a sheet.
// Dragging is not the only way to dismiss a sheet; callers must also provide
// Escape and a visible close control (.claude/rules/frontend.md).
export function SheetGrab() {
  return <div className={styles.grab} aria-hidden="true" />;
}
