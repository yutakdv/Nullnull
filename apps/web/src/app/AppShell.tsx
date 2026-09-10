import { Outlet, useLocation, useNavigate } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import { TabBar, type TabKey } from '../shared/ui/components/index.js';
import styles from './AppShell.module.css';

// The app chrome: content plus the four-tab bar (C12).
//
// Every Figma tab destination carries the bar (S03 feed, S07-1 trip, S11 live,
// S14 profile). The flows that do not are the ones a user is *inside* rather
// than *at* — onboarding, the create wizard, an edit screen, a sub-page reached
// by a back control. Showing the bar there invites a tap that abandons work in
// progress, which is why the frames omit it.
//
// The route table decides, not each screen: a screen that had to remember to
// render its own chrome is a screen that will eventually forget, which is
// exactly what happened before this existed.

/** Where each tab goes. `trip` resolves at press time (see below). */
const TAB_PATHS: Record<Exclude<TabKey, 'trip'>, string> = {
  home: '/feed',
  live: '/live',
  profile: '/profile',
};

function activeTab(pathname: string): TabKey {
  if (pathname.startsWith('/profile')) return 'profile';
  if (pathname.startsWith('/live')) return 'live';
  if (pathname.startsWith('/trip')) return 'trip';
  return 'home';
}

export interface AppShellProps {
  /** Set on routes that are a tab destination. */
  tabs?: boolean;
}

export function AppShell({ tabs = false }: AppShellProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const { t } = useI18n();

  return (
    <div className={styles.shell}>
      <main className={styles.content} id="main">
        <Outlet />
      </main>
      {tabs ? (
        <div className={styles.tabs}>
          <TabBar
            active={activeTab(location.pathname)}
            labels={{
              home: t('nav.tab.home'),
              trip: t('nav.tab.trip'),
              live: t('nav.tab.live'),
              profile: t('nav.tab.profile'),
            }}
            navLabel={t('nav.tabs')}
            onSelect={(key) => {
              if (key === 'trip') {
                // There is no single "my trip" URL: the active trip comes from
                // the owner profile (BA-011's activeTripId), which is not wired
                // yet. Until it is, the tab goes to the list on the profile
                // rather than guessing at a trip id.
                void navigate('/profile');
                return;
              }
              void navigate(TAB_PATHS[key]);
            }}
          />
        </div>
      ) : null}
    </div>
  );
}
