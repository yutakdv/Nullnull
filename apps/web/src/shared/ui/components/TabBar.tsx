import { IconHome, IconLive, IconProfile, IconTrip } from '../icons/index.js';
import styles from './TabBar.module.css';

// Figma: `Nav / TabBar` (C12). P0 tabs: 홈, 내 여행, 라이브, 내 정보.
//
// The 검색 tab arrives in P1 and is not rendered here at all, since an
// unsupported tab that navigates nowhere is worse than an absent one.
export type TabKey = 'home' | 'trip' | 'live' | 'profile';

export interface TabBarProps {
  active: TabKey;
  onSelect?: (key: TabKey) => void;
}

const TABS: ReadonlyArray<{
  key: TabKey;
  label: string;
  Icon: typeof IconHome;
}> = [
  { key: 'home', label: '홈', Icon: IconHome },
  { key: 'trip', label: '내 여행', Icon: IconTrip },
  { key: 'live', label: '라이브', Icon: IconLive },
  { key: 'profile', label: '내 정보', Icon: IconProfile },
];

export function TabBar({ active, onSelect }: TabBarProps) {
  return (
    <nav className={styles.bar} aria-label="주요 메뉴">
      {TABS.map(({ key, label, Icon }) => (
        <button
          key={key}
          type="button"
          className={styles.tab}
          data-active={key === active || undefined}
          aria-current={key === active ? 'page' : undefined}
          onClick={() => onSelect?.(key)}
        >
          <Icon size={24} />
          <span className={styles.label}>{label}</span>
        </button>
      ))}
    </nav>
  );
}
