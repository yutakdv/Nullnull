import type { ReactNode } from 'react';
import { IconBack } from '../icons/index.js';
import styles from './NavBar.module.css';

// Figma: `Nav / NavBar` (C49). The page top app bar.
//
// COMPONENT_CATALOG C49: "title, back, leading/trailing actions, safe-area …
// route metadata; browser history를 맹목적으로 `-1` 호출 금지".
//
// That last rule is why `onBack` is a required callback rather than an internal
// `history.go(-1)`: a screen reached by a deep link, a reload, or a redirect has
// no previous entry belonging to this app, and going back one step there leaves
// the user wherever they were before — or on a blank tab. Each screen names its
// own destination instead.

export interface NavBarProps {
  title?: string;
  /** Renders the back control when given. */
  onBack?: () => void;
  /** Accessible name for the back control; the glyph alone is not a name. */
  backLabel?: string;
  /** Trailing controls, e.g. settings. */
  actions?: ReactNode;
}

export function NavBar({ title, onBack, backLabel = '뒤로', actions }: NavBarProps) {
  return (
    <header className={styles.bar}>
      {onBack ? (
        <button
          aria-label={backLabel}
          className={styles.back}
          onClick={onBack}
          type="button"
        >
          <IconBack size={24} />
        </button>
      ) : null}
      {/* Not an <h1>: the screen below owns the page heading, and two h1s — or
          a heading that duplicates the one in the content — makes the outline
          wrong for a screen reader. */}
      {title === undefined ? null : <span className={styles.title}>{title}</span>}
      {actions === undefined ? null : <div className={styles.actions}>{actions}</div>}
    </header>
  );
}
