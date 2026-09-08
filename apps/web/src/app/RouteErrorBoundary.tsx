import { Link, isRouteErrorResponse, useRouteError } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import { problemPresentation, toProblem } from '../shared/api/index.js';
import styles from './RouteErrorBoundary.module.css';

// Route-level boundary. Attached to the layout route so a crashed screen keeps
// the shell mounted and navigable — an app-level-only boundary would blank the
// whole app, which is what routes.tsx means by "Nothing paints blank".
//
// This catches render and lifecycle exceptions, which are FE bugs. An API
// Problem is a different layer: an expected state a screen handles with the
// code's own recovery. The two only meet here because react-router routes
// loader throws through errorElement, so a loader that threw a Problem lands in
// this component and gets the contract's copy rather than the generic message.

function isErrorLike(value: unknown): value is Error {
  return value instanceof Error;
}

export function RouteErrorBoundary() {
  const error = useRouteError();
  const { t } = useI18n();

  // A loader threw a Problem: show the contract's message and CTA, not "this
  // screen could not be shown".
  const problem = toProblem(error);
  if (problem) {
    const { message, ctaLabel, requestId } = problemPresentation(problem, t);
    return (
      <section className={styles.screen} aria-labelledby="route-error-heading">
        <h1 id="route-error-heading" className={styles.title}>
          {message}
        </h1>
        <div className={styles.actions}>
          <Link className={styles.action} to="/">
            {ctaLabel}
          </Link>
        </div>
        {requestId ? (
          <p className={styles.requestId}>
            {t('error.requestId')}: {requestId}
          </p>
        ) : null}
      </section>
    );
  }

  // A 404 from the router itself already has its own screen; anything else
  // reaching here is a render failure.
  const status = isRouteErrorResponse(error) ? error.status : undefined;

  return (
    <section className={styles.screen} aria-labelledby="route-error-heading">
      <h1 id="route-error-heading" className={styles.title}>
        {t('app.error.title')}
      </h1>
      <p className={styles.body} data-testid="route-error">
        {t('app.error.body')}
      </p>
      <div className={styles.actions}>
        <button
          type="button"
          className={styles.action}
          onClick={() => {
            window.location.reload();
          }}
        >
          {t('app.error.retry')}
        </button>
        <Link className={styles.action} to="/">
          {t('app.error.home')}
        </Link>
      </div>
      {/* Diagnostics for the developer, never the raw message to the user:
          an exception string can carry internals the user should not see. */}
      {import.meta.env.DEV && (isErrorLike(error) || status) ? (
        <p className={styles.requestId}>
          {status ? `HTTP ${status}` : isErrorLike(error) ? error.message : null}
        </p>
      ) : null}
    </section>
  );
}
