import { useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useConfirmTripImport,
  useParseTripImport,
  useRemapTripImport,
  type ImportDraftWithETag,
} from '../../shared/api/index.js';
import { BottomCta, NavBar } from '../../shared/ui/index.js';
import { EMPTY_DRAFT, toCreateRequest, type WizardDraft } from './wizard.js';
import wizard from './TripWizardScreen.module.css';
import styles from './ImportPasteScreen.module.css';

// Figma: S02-4C-A 붙여넣기 `401:1221` (FE-104, FR-TRC-08).
//
// Paste an itinerary, let the server read it, fix what it could not place,
// then turn it into a trip.
//
// THE RAW TEXT NEVER LEAVES THIS COMPONENT except as a request body. It is not
// written to sessionStorage (FIGMA_HANDOFF's wizard rule excludes it from
// persistence by name), not put in the URL, not logged, and not kept after the
// parse returns — `setRaw('')` on success is not tidiness, it is invariant 10.
// The server is held to the same rule: the contract says the response must not
// echo rawText back.
//
// `400:1201` (입력 방식 선택) is NOT built here. It is a step-4 branch screen
// whose confirm boundary is still open in FCR-018, so this is reached from the
// wizard's own step 3 as a secondary action instead of inventing that screen.
// The alternative was another route nothing links to, which is exactly what
// `/start/must-visit` was until #185 folded must-visit into the wizard as
// step 4 and deleted it.

export function ImportPasteScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();

  const [raw, setRaw] = useState('');
  const [draft, setDraft] = useState<ImportDraftWithETag | null>(null);
  const [announced, setAnnounced] = useState<string | null>(null);

  const parse = useParseTripImport();
  const remap = useRemapTripImport(draft?.draft.id ?? null);
  const confirm = useConfirmTripImport(draft?.draft.id ?? null);

  // One key per parse attempt, held across retries of THAT attempt so a retry
  // after a lost response re-reads the same paste instead of starting a second
  // parse. Keyed by the text, so editing it makes a new command.
  const parseKey = useRef<{ for: string; key: string } | null>(null);
  const confirmKey = useRef<string | null>(null);

  const current = draft?.draft ?? null;
  const ready = current !== null && current.status === 'READY';

  function runParse() {
    const text = raw.trim();
    if (text === '') return;
    if (parseKey.current?.for !== text) {
      parseKey.current = { for: text, key: crypto.randomUUID() };
    }
    parse.mutate(
      {
        request: {
          rawText: text,
          locale: 'ko-KR',
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        },
        idempotencyKey: parseKey.current.key,
      },
      {
        onSuccess: (result) => {
          setDraft(result);
          // The paste is done its job. Holding it after this would be a copy
          // of the user's itinerary living in memory for no purpose.
          setRaw('');
          parseKey.current = null;
        },
      },
    );
  }

  /** Sends one correction and keeps the returned draft and its new ETag. */
  function sendUpdate(
    update: components['schemas']['RemapImportRequest']['updates'][number],
    message?: string,
  ) {
    if (!draft) return;
    remap.mutate(
      { updates: [update], etag: draft.etag },
      {
        onSuccess: (result) => {
          setDraft(result);
          if (message !== undefined) setAnnounced(message);
        },
      },
    );
  }

  function runConfirm() {
    if (!draft || !ready) return;
    confirmKey.current ??= crypto.randomUUID();
    // The wizard's own draft supplies title, planning level and interests:
    // FIGMA_HANDOFF says this flow has no separate title step, and a second
    // place to collect them would be a second source for the same three
    // fields.
    const wizardDraft: WizardDraft = {
      ...EMPTY_DRAFT,
      startDate: draft.draft.dates.startDate ?? null,
      endDate: draft.draft.dates.endDate ?? null,
      planningLevel: 'MOSTLY_PLANNED',
    };
    const created = toCreateRequest(wizardDraft, 'Asia/Seoul');
    confirm.mutate(
      {
        request: {
          title: draft.draft.title ?? t('import.title'),
          planningLevel: 'MOSTLY_PLANNED',
          interests: created?.interests ?? [],
        },
        etag: draft.etag,
        idempotencyKey: confirmKey.current,
      },
      {
        onSuccess: (result) => {
          confirmKey.current = null;
          void navigate(`/trip/${result.trip.id}`, { replace: true });
        },
      },
    );
  }

  const expired =
    (isProblem(remap.error) && remap.error.code === 'IMPORT_DRAFT_EXPIRED') ||
    (isProblem(confirm.error) && confirm.error.code === 'IMPORT_DRAFT_EXPIRED');
  const changed =
    (isProblem(remap.error) && remap.error.code === 'IMPORT_DRAFT_CHANGED') ||
    (isProblem(confirm.error) && confirm.error.code === 'IMPORT_DRAFT_CHANGED');

  return (
    <section className={wizard.screen} aria-labelledby="import-heading">
      <NavBar
        backLabel={t('wizard.back')}
        onBack={() => {
          void navigate('/start');
        }}
      />

      <div className={wizard.head}>
        <h1 className={wizard.title} id="import-heading">
          {t('import.title')}
        </h1>
        <p className={wizard.lead}>
          {current === null ? t('import.lead') : t('import.review.lead')}
        </p>
      </div>

      {/* The screen is announced at one place, above the fold, so a correction
          is heard whether or not the row that caused it survived. */}
      <p aria-live="polite" className={styles.note} role="status">
        {announced ?? ''}
      </p>

      {current === null ? (
        <>
          <label className={styles.note} htmlFor="import-text">
            {t('import.label')}
          </label>
          <textarea
            className={styles.textarea}
            id="import-text"
            onChange={(event) => {
              setRaw(event.target.value);
            }}
            placeholder={t('import.placeholder')}
            rows={10}
            value={raw}
          />
          {/* Said before the paste, not after: it is the reason someone is
              willing to paste at all. */}
          <p className={styles.note}>{t('import.privacy')}</p>

          {parse.isError ? (
            <p className={wizard.hint} role="alert">
              {t('import.parseFailed')}
            </p>
          ) : null}

          <BottomCta
            disabled={raw.trim() === '' || parse.isPending}
            label={parse.isPending ? t('import.parsing') : t('import.parse')}
            onClick={runParse}
          />
        </>
      ) : (
        <>
          <p className={styles.note}>
            {t('import.review.items', { count: current.items.length })}
            {current.unresolved.length > 0
              ? ` · ${t('import.review.unresolved', {
                  count: current.unresolved.length,
                })}`
              : ` · ${t('import.review.ready')}`}
          </p>

          <ul className={styles.rows}>
            {current.items.map((item) => (
              <li className={styles.row} key={item.clientKey}>
                <span className={styles.rowText}>
                  <span className={styles.rowTitle}>
                    {item.place?.name ?? item.originalLabel ?? ''}
                  </span>
                  <span className={styles.note}>
                    {item.date ?? t('import.item.noDate')}
                    {/* `startTime` is nullable AND optional in the schema, so
                        an absent field is not the same value as a null one.
                        Checking only for null would read `.slice` off
                        undefined for an item the parser gave no time at all. */}
                    {item.startTime == null ? '' : ` · ${item.startTime.slice(0, 5)}`}
                  </span>
                </span>
                <button
                  aria-label={t('import.item.dismiss', {
                    name: item.place?.name ?? item.originalLabel ?? '',
                  })}
                  className={styles.drop}
                  disabled={remap.isPending}
                  onClick={() => {
                    sendUpdate({ clientKey: item.clientKey, dismissed: true });
                  }}
                  type="button"
                >
                  <span aria-hidden="true">✕</span>
                </button>
              </li>
            ))}
          </ul>

          {/* Unresolved tokens. `line` is what locates them: the server never
              echoes the pasted line back, and after a refresh the client no
              longer holds the paste either. */}
          <ul className={styles.rows}>
            {current.unresolved.map((token) => (
              <li className={styles.row} key={token.clientKey}>
                <span className={styles.rowText}>
                  <span className={styles.rowTitle}>
                    {token.label === '' ? t('import.token.noLabel') : token.label}
                  </span>
                  <span className={styles.note}>
                    {t('import.token.line', { line: token.line })}
                  </span>
                </span>
                <span className={styles.actions}>
                  {token.suggestions.map((place) => (
                    <button
                      className={styles.pick}
                      disabled={remap.isPending}
                      key={place.id}
                      onClick={() => {
                        sendUpdate({
                          clientKey: token.clientKey,
                          placeId: place.id,
                          date: current.dates.startDate ?? null,
                        });
                      }}
                      type="button"
                    >
                      {t('import.token.pick', { name: place.name })}
                    </button>
                  ))}
                  {/* The dead end #223 closed: a line with no label and no
                      suggestion cannot be resolved, so withdrawing it is the
                      only way it stops blocking READY. */}
                  <button
                    className={styles.drop}
                    disabled={remap.isPending}
                    onClick={() => {
                      sendUpdate(
                        { clientKey: token.clientKey, dismissed: true },
                        t('import.token.dismissed', { line: token.line }),
                      );
                    }}
                    type="button"
                  >
                    {t('import.token.dismiss')}
                  </button>
                </span>
              </li>
            ))}
          </ul>

          {expired ? (
            <p className={wizard.hint} role="alert">
              {t('import.expired')}
            </p>
          ) : null}
          {changed ? (
            <p className={wizard.hint} role="alert">
              {t('import.changed')}
            </p>
          ) : null}
          {confirm.isError && !expired && !changed ? (
            <p className={wizard.hint} role="alert">
              {t('import.confirmFailed')}
            </p>
          ) : null}

          <BottomCta
            disabled={!ready || confirm.isPending}
            label={confirm.isPending ? t('import.confirming') : t('import.confirm')}
            onClick={runConfirm}
            secondary={
              ready ? undefined : (
                <p className={styles.note}>{t('import.confirmBlocked')}</p>
              )
            }
          />
        </>
      )}
    </section>
  );
}
