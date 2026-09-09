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
  const { isSuccess, isError, isFetching, refetch } = useSessionBootstrap();

  useEffect(() => {
    if (isSuccess) void navigate('/language', { replace: true });
  }, [isSuccess, navigate]);

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
