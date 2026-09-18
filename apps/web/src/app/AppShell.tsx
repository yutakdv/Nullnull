import { useEffect } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import { useQuery } from '@tanstack/react-query';
import type { components } from '@nullnull/api-client';
import {
  bootstrapSession,
  isProblem,
  sessionQueryKey,
  useCsrfToken,
} from '../shared/api/index.js';
import { TabBar, type TabKey } from '../shared/ui/components/index.js';
import styles from './AppShell.module.css';

type SessionBootstrap = components['schemas']['SessionBootstrap'];

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

/**
 * Which tab is current.
 *
 * `fromTab` is the tab the traveller actually pressed, carried in history
 * state, and it wins over the path for one reason: the 내 여행 tab can land on
 * /profile when there is no active trip, and highlighting 내 정보 there tells
 * the user they pressed something other than what they pressed. The press is
 * the fact; the path is a consequence of it.
 *
 * Only that one case sets it. A direct visit to /profile, a reload, or a link
 * from anywhere else carries no state and reads from the path as before.
 */
function activeTab(pathname: string, fromTab?: TabKey): TabKey {
  if (fromTab) return fromTab;
  if (pathname.startsWith('/profile')) return 'profile';
  if (pathname.startsWith('/live')) return 'live';
  if (pathname.startsWith('/trip')) return 'trip';
  return 'home';
}

/** History state the tab bar sets when a press lands somewhere unexpected. */
interface TabNavState {
  fromTab?: TabKey;
  /** Scroll target on arrival, so the fallback shows what was asked for. */
  focus?: string;
}

export interface AppShellProps {
  /** Set on routes that are a tab destination. */
  tabs?: boolean;
}

export function AppShell({ tabs = false }: AppShellProps) {
  const location = useLocation();
  const navState = location.state as TabNavState | null;
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
  // A 401 with `missingCredential: SESSION_COOKIE` means the request carried no
  // session cookie AT ALL — a first visit, a cleared browser, or a proxy that
  // stripped the header. There is no session to strand, so this tab may start
  // one (#240, BA-010).
  //
  // The distinction is the whole point and it has to stay narrow. The field is
  // NEVER set when a cookie was sent, so its absence says nothing about why
  // that cookie failed: expired, revoked, forged, malformed and never-issued
  // all answer the same way. Bootstrapping on those would mint a DIFFERENT
  // anonymous owner (SessionSafetyIT.expiration) and strand every trip the
  // traveller had — which is why this reads the field rather than `!bootstrapped`,
  // and why it must not be widened to "any UNAUTHORIZED".
  //
  // Deep links were the visible cost: every Playwright context is a fresh
  // browser, so /feed, /profile and /live opened on the session-ended screen
  // and 13 e2e specs failed on it. The client could not tell the two apart
  // until the server said which one this was.
  const noCookieSent =
    isProblem(csrf.error) &&
    csrf.error.code === 'UNAUTHORIZED' &&
    csrf.error.missingCredential === 'SESSION_COOKIE';

  // Same queryKey as `useSessionBootstrap`, which is what keeps the contract's
  // "at most one new session per page load" true by construction rather than by
  // a flag someone has to remember: react-query dedupes by key, and the entry
  // is `staleTime: Infinity`, so SplashScreen and this share one in-flight
  // request and one result. `enabled` only decides whether THIS observer may
  // start it.
  const session = useQuery<SessionBootstrap>({
    queryKey: sessionQueryKey,
    queryFn: bootstrapSession,
    enabled: noCookieSent,
    staleTime: Infinity,
    retry: false,
  });
  const bootstrapped = session.isSuccess;
  // An ended session, now that the two are distinguishable: a 401 whose request
  // DID carry a cookie, and no bootstrap has succeeded in this tab.
  const sessionGone =
    isProblem(csrf.error) &&
    csrf.error.code === 'UNAUTHORIZED' &&
    !noCookieSent &&
    !bootstrapped;

  // Where the 내 여행 tab goes, read from the same cache entry rather than
  // fetched: `useSessionBootstrap` owns it and asks once per load, and a second
  // caller here would POST /demo/sessions again — which mints a different
  // anonymous owner and strands the trips this tab is trying to open.
  //
  // `useUpdatePreferences` writes the owner back into this entry after a PATCH,
  // so creating a trip moves the tab without a reload.
  const activeTripId = session.data?.owner.activeTripId ?? null;

  // Brings the asked-for section into view after a tab press landed on a screen
  // that holds more than it.
  //
  // In AppShell rather than in the destination: the screen should not have to
  // know which tab sent someone to it, and any future fallback gets this for
  // free. Runs after paint because the section belongs to the route that is
  // still rendering when this effect is queued.
  //
  // `block: 'start'` and not `focus()`: moving focus would announce the heading
  // and strand a keyboard user mid-page, while scrolling shows the list and
  // leaves the tab order alone. `behavior: 'auto'` respects a reduced-motion
  // preference by not animating at all.
  const focusTarget = navState?.focus ?? null;
  useEffect(() => {
    if (!focusTarget) return;
    const node = document.getElementById(focusTarget);
    node?.scrollIntoView({ block: 'start', behavior: 'auto' });
  }, [focusTarget, location.key]);

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
            active={activeTab(location.pathname, navState?.fromTab)}
            labels={{
              home: t('nav.tab.home'),
              trip: t('nav.tab.trip'),
              live: t('nav.tab.live'),
              profile: t('nav.tab.profile'),
            }}
            navLabel={t('nav.tabs')}
            onSelect={(key) => {
              if (key === 'trip') {
                // There is no single "my trip" URL — the tab resolves at press
                // time to the owner's active trip (BA-011's `activeTripId`),
                // which the wizard sets on every create.
                //
                // The fallback is the trip list on the profile, and it is a
                // real state rather than a stopgap: a traveller who has made no
                // trip has none to open, and one whose active trip was deleted
                // has the pointer cleared by `owners.active_trip_id`'s ON
                // DELETE SET NULL. Both land on the list, which is where a trip
                // gets picked.
                if (activeTripId) {
                  void navigate(`/trip/${activeTripId}`);
                  return;
                }
                // The fallback says where the press came from, so the bar keeps
                // 내 여행 lit and the profile scrolls to its trip list instead
                // of opening on the account block. Without this the tab reads
                // as broken: a different tab lights up and the trips sit below
                // the fold.
                void navigate('/profile', {
                  state: { fromTab: 'trip', focus: 'profile-trips-heading' },
                });
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
