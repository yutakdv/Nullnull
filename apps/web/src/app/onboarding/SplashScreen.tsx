import { useEffect } from 'react';
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

export function SplashScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { data, isSuccess, isError, isFetching, refetch } = useSessionBootstrap();

  useEffect(() => {
    if (!isSuccess) return;
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
  }, [data, isSuccess, navigate]);

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
        {isFetching ? (
          <p className={styles.message} role="status">
            {t('app.name')}
          </p>
        ) : null}
      </div>
    </section>
  );
}
