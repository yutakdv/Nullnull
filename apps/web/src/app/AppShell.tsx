import { Outlet, useLocation, useNavigate } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import { isProblem, useCsrfToken } from '../shared/api/index.js';
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

  // FR-SES-03. This is the root element of every route, which is why the call
  // lives here: only the splash screen bootstraps, so a refresh or a deep link
  // onto /feed or /profile used to leave the tab with no CSRF token and every
  // mutation would have been rejected. The hook asks only when the token is
  // missing, and only for the session the cookie already names — it never
  // bootstraps a replacement, because doing that on an expired session creates
  // a different anonymous owner and strands the user's trips.
  //
  // The result is READ, not discarded. PROBLEM_POLICY marks UNAUTHORIZED
  // severity `screen` with recovery `restart-session`, and dropping the error
  // meant an expired session rendered the ordinary screen: the reissue 401'd
  // with retry:false, the screen's own fetches 401'd too, and a screen that
  // gates on isSuccess — the feed does — sat on its loading state for ever
  // with no error, no retry and no way back. Reproduced in a browser.
  const csrf = useCsrfToken();
  const sessionGone = isProblem(csrf.error) && csrf.error.code === 'UNAUTHORIZED';

  if (sessionGone) {
    // Only 401. A network failure is not an ended session, and replacing the
    // whole screen for one would hide a recoverable error behind a restart.
    return (
      <div className={styles.shell}>
        <main className={styles.content} id="main">
          <section aria-labelledby="session-heading" className={styles.session}>
            {/* role="alert" on the heading itself: two elements carrying the
                same sentence would have a screen reader read it twice. */}
            <h1 className={styles.sessionTitle} id="session-heading" role="alert">
              {t('session.expired')}
            </h1>
            <p className={styles.sessionNote}>{t('session.expiredNote')}</p>
            {/* The restart is the user's deliberate act. Bootstrapping here on
                their behalf would mint a different anonymous owner and strand
                the trips this message just promised (SessionSafetyIT). Sending
                them to the splash screen makes it a choice. */}
            <button
              className={styles.sessionRestart}
              onClick={() => {
                void navigate('/');
              }}
              type="button"
            >
              {t('session.restart')}
            </button>
          </section>
        </main>
      </div>
    );
  }

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
