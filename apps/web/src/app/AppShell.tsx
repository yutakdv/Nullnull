import { Outlet, useLocation, useNavigate } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import { useQuery } from '@tanstack/react-query';
import { isProblem, sessionQueryKey, useCsrfToken } from '../shared/api/index.js';
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
  // A 401 is only an ENDED session when no token exists afterwards.
  //
  // On a first visit there is no cookie yet, so this reissue 401s by design
  // while SplashScreen's POST /demo/sessions mints the session moments later.
  // Measured in the gate's own container: `401 /session/csrf` at 50ms,
  // `201 /demo/sessions` at 55ms - and the shell stayed on the "session ended"
  // screen for ever, because the error was read once and nothing cleared it.
  // Every route rendered that screen, which is why shell.spec looked for
  // `not-found-heading` and found `session-heading` instead (#240).
  //
  // `currentCsrfToken()` is what distinguishes the two: bootstrapSession sets
  // it on success (session.ts:64), so a token present after the 401 means the
  // bootstrap won and this tab is usable. A token still absent means the
  // reissue failed for a session that really is gone, which is the case this
  // screen exists for and which SessionSafetyIT.expiration pins.
  // Subscribed, not read - and WITHOUT starting a bootstrap of its own.
  //
  // `currentCsrfToken()` is a module variable, so reading it during render
  // answers with whatever was true when this render started, and nothing
  // re-renders when SplashScreen's bootstrap fills it in moments later.
  // Measured in the gate's container: `201 /demo/sessions` at 39ms, the token
  // set, and the screen stayed on "session ended" for ever.
  //
  // `useQuery` here would fix the reactivity and break something worse: it has
  // no `enabled` guard, so every deep link would POST /demo/sessions and an
  // expired session would silently get a DIFFERENT anonymous owner
  // (SessionSafetyIT.expiration), stranding the trips this screen promises are
  // still there. `useQueryState` only observes the cache entry SplashScreen
  // owns - it never creates one.
  // `enabled: false` is what makes this an observer and not a second caller:
  // the hook subscribes to the cache entry and never runs a queryFn.
  const bootstrapped = useQuery({ queryKey: sessionQueryKey, enabled: false }).isSuccess;
  const sessionGone =
    isProblem(csrf.error) && csrf.error.code === 'UNAUTHORIZED' && !bootstrapped;

  if (sessionGone) {
    // Only 401. A network failure is not an ended session, and replacing the
    // whole screen for one would hide a recoverable error behind a restart.
    return (
      <div className={styles.shell}>
        <main className={styles.content} id="main">
          <section aria-labelledby="session-heading" className={styles.session}>
            {/* The heading stays a heading.

                `role="alert"` used to sit on this <h1> to avoid two elements
                reading the same sentence twice. It works for the announcement
                and costs the outline: an explicit role REPLACES the implicit
                one, so the <h1> stopped being a heading in the accessibility
                tree. A screen-reader user navigating by headings found none,
                and `getByRole('heading', { level: 1 })` found none either —
                which is why every spec that checks the h1's id reported
                "element(s) not found" on this screen (#240).

                The announcement moves to a container that wraps both lines, so
                the sentence is still read on arrival and the heading is still
                a heading. `aria-live="assertive"` rather than role="alert"
                because the region already exists when this branch renders;
                `role="alert"` on a wrapper would add a second announcement of
                text the section is also labelled by. */}
            <div aria-live="assertive">
              <h1 className={styles.sessionTitle} id="session-heading">
                {t('session.expired')}
              </h1>
              <p className={styles.sessionNote}>{t('session.expiredNote')}</p>
            </div>
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
