import { useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { isProblem, isRunning, useOptimization } from '../../shared/api/index.js';
import { NavBar } from '../../shared/ui/index.js';
import styles from './OptimizationRunScreen.module.css';

// Figma: S09-1 계산 중 `415:2413` (FR-OPT-03, FE-502).
//
// This screen is where a submitted run lives until it settles. Before it
// existed the route rendered a placeholder, so pressing 대안 찾아보기 landed on
// a page whose entire content was the debug string "optimization" — and every
// 최적화 결과 보기 link in the profile history went to the same place.
//
// Three rules shape it:
//
//   - Nothing here changes the itinerary. A run is a preview until the user
//     applies it, and a failure, an expiry or a still-queued run all leave the
//     trip exactly as it was (invariants 3 and 4). The screen says so in words
//     rather than leaving the user to infer it, because "계산하지 못했어요" on
//     its own reads as though something might have half-happened.
//   - Leaving is navigation, not cancellation. FCR-014 settled that P0 has no
//     cancel operation, so the control says 내 여행으로 돌아가기 and the copy
//     states that the run continues and can be reopened by URL. Naming it
//     취소 would promise an operation the contract does not have.
//   - No invented numbers. FCR-005 removed route/time copy while P0 has no
//     route provider, so the steps name crowd and locks only.
//
// NOT BUILT HERE: the READY preview itself — before/after, metrics, the
// decision bar — is FE-503/FE-505 and needs BA-051 to compute proposals and
// BA-052 to record a decision. A READY run therefore reports that the result
// arrived and stops. Rendering a preview from an empty proposals array would
// mean inventing the comparison invariant 8 forbids.

type OptimizationFailure = components['schemas']['OptimizationFailure'];

/**
 * The failure codes the contract defines, each with its own sentence.
 *
 * Takes the failure rather than the run: naming OptimizationRun in a signature
 * fails to compile with "two different types with this name exist", because
 * the run nests the OptimizationChange union — the same trap
 * useCreateOptimization documents in session.ts.
 */
function failureMessage(failure: OptimizationFailure | null | undefined) {
  if (!failure) return null;
  return `run.failure.${failure.code}` as MessageKey;
}

export function OptimizationRunScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { tripId = null, runId = null } = useParams<{
    tripId: string;
    runId: string;
  }>();
  const run = useOptimization(runId);

  function back() {
    void navigate(tripId === null ? '/feed' : `/trip/${tripId}`);
  }

  const frame = (body: React.ReactNode) => (
    <section aria-labelledby="run-heading" className={styles.screen}>
      <NavBar backLabel={t('run.leave')} onBack={back} />
      <h1 className={styles.title} id="run-heading">
        {t('run.title')}
      </h1>
      {body}
    </section>
  );

  if (run.isPending) {
    return frame(
      <p className={styles.state} role="status">
        {t('run.loading')}
      </p>,
    );
  }

  if (run.isError) {
    // An expired preview and a missing run are terminal: there is nothing to
    // retry, and the contract says an expiry "does not apply to an already
    // recorded decision", so the itinerary is untouched either way.
    const problem = isProblem(run.error) ? run.error : null;
    const expired = problem?.code === 'PREVIEW_EXPIRED';
    const missing = problem?.code === 'NOT_FOUND';
    const terminal = expired || missing;
    return frame(
      <>
        <p className={styles.status} role="alert">
          {expired ? t('run.expired') : missing ? t('run.notFound') : t('run.error')}
        </p>
        {expired ? <p className={styles.note}>{t('run.unchanged')}</p> : null}
        <div className={styles.actions}>
          {terminal ? null : (
            <button
              className={styles.primary}
              onClick={() => {
                void run.refetch();
              }}
              type="button"
            >
              {t('optimize.retry')}
            </button>
          )}
          {expired ? (
            <button
              className={styles.primary}
              onClick={() => {
                void navigate(`/trip/${tripId ?? ''}/optimize`);
              }}
              type="button"
            >
              {t('run.recompute')}
            </button>
          ) : null}
          <button className={styles.secondary} onClick={back} type="button">
            {t('run.leave')}
          </button>
        </div>
      </>,
    );
  }

  const detail = run.data;
  const working = isRunning(detail.status);
  const failure = failureMessage(detail.failure);

  return frame(
    <>
      {/* One live region for the whole run, so a screen reader hears the
          state change instead of only the first state it landed on. */}
      <p aria-live="polite" className={styles.status} role="status">
        {detail.status === 'QUEUED'
          ? t('run.queued')
          : detail.status === 'RUNNING'
            ? t('run.running')
            : detail.status === 'READY'
              ? t('run.ready')
              : failure
                ? t('run.failed')
                : t('run.ready')}
      </p>

      {working ? (
        <>
          <p className={styles.lead}>{t('run.working')}</p>
          {/* FCR-005: crowd and locks only. P0 has no route provider, so a
              `경로 계산` step would describe work nothing does. */}
          <ul className={styles.steps}>
            <li className={styles.step} data-active>
              <span aria-hidden="true" className={styles.dot} />
              {t('run.step.crowd')}
            </li>
            <li className={styles.step} data-active={detail.status === 'RUNNING'}>
              <span aria-hidden="true" className={styles.dot} />
              {t('run.step.locks')}
            </li>
          </ul>
        </>
      ) : null}

      {failure ? (
        <>
          <p className={styles.lead}>{t(failure)}</p>
          {/* Invariant 4, stated: a failed run changes nothing. */}
          <p className={styles.note}>{t('run.unchanged')}</p>
        </>
      ) : null}

      {detail.status === 'READY' ? (
        // FE-503 builds the preview; BA-051 has to compute the proposals
        // first. Saying so is honest, where a blank panel would look broken.
        <p className={styles.note}>{t('run.readyPending')}</p>
      ) : null}

      <div className={styles.actions}>
        {failure && detail.failure?.retryable ? (
          <button
            className={styles.primary}
            onClick={() => {
              void navigate(`/trip/${tripId ?? ''}/optimize`);
            }}
            type="button"
          >
            {t('run.recompute')}
          </button>
        ) : null}
        <button className={styles.secondary} onClick={back} type="button">
          {t('run.leave')}
        </button>
      </div>

      {/* FCR-014: leaving is navigation. The run is not cancelled by it, and
          the URL brings the user back to it. */}
      {working ? <p className={styles.note}>{t('run.keepsRunning')}</p> : null}
    </>,
  );
}
