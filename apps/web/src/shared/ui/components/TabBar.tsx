import { IconHome, IconLive, IconProfile, IconTrip } from '../icons/index.js';
import styles from './TabBar.module.css';

// Figma: `Nav / TabBar` (C12). P0 tabs: 홈, 내 여행, 라이브, 내 정보.
//
// The 검색 tab arrives in P1 and is not rendered here at all, since an
// unsupported tab that navigates nowhere is worse than an absent one.
export type TabKey = 'home' | 'trip' | 'live' | 'profile';

export interface TabBarProps {
  active: TabKey;
  /** Localised label per tab, in the caller's current locale. */
  labels: Record<TabKey, string>;
  /** Accessible name for the bar itself. */
  navLabel: string;
  onSelect?: (key: TabKey) => void;
}

// Labels come from the caller, not from here: hardcoding Korean left the bar
// in Korean while the rest of the app switched to English, which is worse than
// an untranslated string elsewhere because it is on every screen.
const TABS: ReadonlyArray<{ key: TabKey; Icon: typeof IconHome }> = [
  { key: 'home', Icon: IconHome },
  { key: 'trip', Icon: IconTrip },
  { key: 'live', Icon: IconLive },
  { key: 'profile', Icon: IconProfile },
];

export function TabBar({ active, labels, navLabel, onSelect }: TabBarProps) {
  return (
    <nav className={styles.bar} aria-label={navLabel}>
      {TABS.map(({ key, Icon }) => (
        <button
          key={key}
          type="button"
          className={styles.tab}
          data-active={key === active || undefined}
          aria-current={key === active ? 'page' : undefined}
          onClick={() => onSelect?.(key)}
        >
          <Icon size={24} />
          <span className={styles.label}>{labels[key]}</span>
        </button>
      ))}
    </nav>
  );
}
