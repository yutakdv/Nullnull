import { useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { useDeletionStatus, useRequestDeletion } from '../../shared/api/index.js';
import styles from './DeletionSection.module.css';

type DeletionStatus = components['schemas']['DeletionRequestStatus']['status'];

// FR-SES-04: request deletion of the session and everything it owns, then watch
// the job.
//
// NO FIGMA FRAME YET. FCR-016 covers "S14 전체 데이터 삭제 보조 흐름 … 진입·확인
// ·처리중·부분 실패·완료·복구 variant" and is still Open, so the copy and layout
// here are written from the contract's states and are subject to design review.
// The states themselves are not invented: DeletionRequestStatus fixes all five.
//
// Two contract rules shape this component:
//
//   - Deletion revokes the session immediately, so the status route cannot use
//     the cookie. It carries a one-purpose token instead, and the contract says
//     to keep that token in memory only. The receipt therefore cannot be
//     reopened later, which the copy has to say plainly rather than implying
//     the user can come back to it.
//   - The request is 202, not 200. Accepted is not deleted, so the screen shows
//     job progress rather than declaring success on the response.

// Where a client may stop polling. The contract names exactly two and says so
// in the schema: "COMPLETED and FAILED are the states a client may stop polling
// on. PARTIAL_FAILED is NOT one of them: it means an attempt failed while the
// server still has attempts left, so the server retries on its own and the
// status changes again without any client action."
//
// useDeletionStatus already had this right and kept polling; this list did not,
// so the screen stopped showing progress and offered a retry for work the
// server was still doing.
const TERMINAL: DeletionStatus[] = ['COMPLETED', 'FAILED'];

export function DeletionSection() {
  const { t } = useI18n();
  const [confirming, setConfirming] = useState(false);
  const [requestId, setRequestId] = useState<string | null>(null);
  const request = useRequestDeletion();
  const status = useDeletionStatus(requestId);

  // Before anything is requested: the entry point plus what it costs.
  if (requestId === null) {
    return (
      <section className={styles.section} aria-labelledby="deletion-heading">
        <span className={styles.title} id="deletion-heading">
          {t('deletion.title')}
        </span>
        <p className={styles.note}>{t('deletion.note')}</p>

        {confirming ? (
          <>
            <p className={styles.note} role="alert">
              {t('deletion.confirm.body')}
            </p>
            <div className={styles.confirmRow}>
              <button
                type="button"
                className={styles.danger}
                disabled={request.isPending}
                onClick={() => {
                  request.mutate(undefined, {
                    onSuccess: (receipt) => {
                      setRequestId(receipt.requestId);
                    },
                  });
                }}
              >
                {t('deletion.confirm.yes')}
              </button>
              <button
                type="button"
                className={styles.cancel}
                onClick={() => {
                  setConfirming(false);
                }}
              >
                {t('deletion.confirm.no')}
              </button>
            </div>
          </>
        ) : (
          // Irreversible, so it takes two deliberate actions rather than one.
          <button
            type="button"
            className={styles.action}
            onClick={() => {
              setConfirming(true);
            }}
          >
            {t('deletion.request')}
          </button>
        )}

        {request.isError ? (
          <p className={`${styles.note} ${styles.problem}`} role="alert">
            {t('deletion.failed')}
          </p>
        ) : null}
      </section>
    );
  }

  const current = status.data?.status ?? 'ACCEPTED';
  // PARTIAL_FAILED is not a problem the user can act on — it is the server
  // still working. Only FAILED is final and bad.
  const isTerminalProblem = current === 'FAILED';

  return (
    <section className={styles.section} aria-labelledby="deletion-heading">
      <span className={styles.title} id="deletion-heading">
        {t('deletion.requested')}
      </span>

      <p
        className={`${styles.status} ${isTerminalProblem ? styles.problem : ''}`}
        // Progress is announced politely; a terminal failure interrupts.
        role={isTerminalProblem ? 'alert' : 'status'}
      >
        {t(`deletion.status.${current}` as MessageKey)}
      </p>

      {/* The token is memory-only, so this really is the only chance to watch
          it. Saying so is part of the design, not a caveat. */}
      <p className={styles.note}>{t('deletion.receiptNote')}</p>

      {status.isError ? (
        <p className={`${styles.note} ${styles.problem}`} role="alert">
          {t('deletion.expired')}
        </p>
      ) : null}

      {/* retryable is the server's judgement; the client does not guess when a
          failed deletion may be repeated. */}
      {isTerminalProblem && status.data?.retryable === true ? (
        <button
          type="button"
          className={styles.action}
          onClick={() => {
            void status.refetch();
          }}
        >
          {t('deletion.retry')}
        </button>
      ) : null}

      {current === 'FAILED' && status.data?.retryable !== true ? (
        <p className={styles.note}>{t('deletion.contact')}</p>
      ) : null}

      {TERMINAL.includes(current) ? null : (
        <p className={styles.note} aria-hidden="true">
          ·
        </p>
      )}
    </section>
  );
}
