import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import { useSessionBootstrap } from '../../shared/api/index.js';
import styles from './SplashScreen.module.css';

// Figma: A-1 splash `388:257`.
//
// FR-ONB-01: bootstrap the session here and move on. The acceptance criteria
// are "no blank screen, no redirect loop, retry offered", so this screen always
// renders the brand and states what is happening rather than waiting silently.
//
// The redirect runs once bootstrap succeeds and replaces this entry in history,
// so Back from the language screen does not land here and bootstrap again.

/**
 * How long the brand stays on screen before a SUCCESSFUL bootstrap redirects.
 *
 * Figma draws A-1 as a screen, not as a transition, but bootstrap against a
 * warm cache resolves in single-digit milliseconds — the wordmark rendered for
 * about one frame and the first thing a traveller actually saw was the language
 * list. This holds the frame long enough to be read.
 *
 * 800ms is the floor of the 800-1200ms band that splash screens conventionally
 * sit in: long enough to register a wordmark, short enough that it does not
 * read as the app being slow. It is a presentation value, so it lives here
 * rather than in a token file, which carries visual scale rather than timing.
 *
 * It is a FLOOR, not a sleep. The clock starts when the screen mounts and runs
 * ALONGSIDE the request, so a bootstrap that takes 600ms adds 200ms of wait and
 * one that takes 900ms adds none. Waiting after the response instead would tax
 * the slowest connections hardest, which is backwards.
 */
const MINIMUM_VISIBLE_MS = 800;

/**
 * Whether the traveller asked for less motion.
 *
 * Read once at mount rather than subscribed to: this decides a single timer
 * that resolves within a second, and a mid-flight preference change has nothing
 * useful to do to it.
 *
 * A held splash is not an animation, but it IS time the interface spends
 * withholding content, which is the same bargain `prefers-reduced-motion`
 * declines elsewhere in the app (styles.css collapses durations to 0.01ms).
 * Somebody who has asked for that gets the redirect as soon as it is ready.
 *
 * Guarded because `matchMedia` is absent in some non-browser environments; its
 * absence means "no stated preference", which is the same branch as no-preference.
 */
function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}

export function SplashScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { data, isSuccess, isError, isFetching, refetch } = useSessionBootstrap();

  // The floor is measured from MOUNT, so it must not restart when the component
  // re-renders on the bootstrap resolving. A ref initialised once holds the
  // origin; state alone would re-run the effect and re-arm the timer.
  const heldSince = useRef<number | null>(null);
  if (heldSince.current === null) {
    heldSince.current = Date.now();
  }

  // Starts true so the very first render cannot redirect, then flips once the
  // floor has elapsed. Deriving this from a timestamp during render instead
  // would not re-render when the deadline passes -- nothing would wake it.
  const [held, setHeld] = useState(() => !prefersReducedMotion());

  useEffect(() => {
    if (!held) return;
    const elapsed = Date.now() - (heldSince.current ?? Date.now());
    const remaining = MINIMUM_VISIBLE_MS - elapsed;
    if (remaining <= 0) {
      setHeld(false);
      return;
    }
    const timer = setTimeout(() => setHeld(false), remaining);
    return () => clearTimeout(timer);
  }, [held]);

  useEffect(() => {
    if (!isSuccess) return;
    // The floor gates the SUCCESS path only. A failed bootstrap renders the
    // alert and the retry immediately -- FR-ONB-01 asks for a retry instead of
    // a blank screen, and making somebody wait to be told the app did not
    // start would be the opposite of that.
    if (held) return;
    // FR-ONB-03's second half: "완료 후 재방문 redirect". Someone who has
    // already read the intro is sent on rather than walked through language
    // and intro again — the flow is for a first visit, and repeating it is the
    // "중복 전환 방지" the handoff asks for at A-3.
    //
    // No extra request buys this. `SessionBootstrap.owner` is an OwnerProfile
    // and `onboardingCompleted` is REQUIRED on it, so the flag arrives with
    // the bootstrap this screen already performs. Reading it from /me instead
    // would make the redirect wait on a second round trip, which is how a
    // splash starts showing a blank frame — the thing FR-ONB-01 forbids.
    //
    // /feed is where IntroScreen sends people too. Choosing a different
    // destination here would give one question two answers.
    const seen = data?.owner.onboardingCompleted === true;
    void navigate(seen ? '/feed' : '/language', { replace: true });
  }, [data, held, isSuccess, navigate]);

  return (
    <section className={styles.screen} aria-labelledby="splash-heading">
      <h1 className={styles.wordmark} id="splash-heading">
        Nullnull
      </h1>
      <p className={styles.tagline}>
        {t('splash.tagline1')}
        <br />
        {t('splash.tagline2')}
      </p>

      {/* Reserved space so the retry appearing does not shift the layout. */}
      <div className={styles.status}>
        {isError ? (
          <>
            <p className={styles.message} role="alert">
              {t('splash.failed')}
            </p>
            <button
              type="button"
              className={styles.retry}
              onClick={() => {
                void refetch();
              }}
            >
              {t('splash.retry')}
            </button>
          </>
        ) : null}
        {/* Held-but-loaded counts as still starting up. Keying this on
            `isFetching` alone made the status text disappear the instant the
            response landed while the screen itself stayed for the rest of the
            floor, so a screen reader announced the app was starting and then
            fell silent on a screen that had not moved. `!isError` keeps the
            two branches mutually exclusive: the alert replaces the status
            rather than stacking under it. */}
        {(isFetching || held) && !isError ? (
          <p className={styles.message} role="status">
            {t('splash.loading')}
          </p>
        ) : null}
      </div>
    </section>
  );
}
