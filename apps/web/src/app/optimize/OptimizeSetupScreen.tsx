import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, useCreateOptimization, useTrip } from '../../shared/api/index.js';
import { Chip, NavBar } from '../../shared/ui/index.js';
import { formatTime } from '../trip/trip-view.js';
import styles from './OptimizeSetupScreen.module.css';

type OptimizationScope = components['schemas']['OptimizationScope'];

// Figma: S09-0 optimization setup `415:2268` (FR-OPT-01, FCR-010).
//
// Two rules shape this screen, and both are the point of it:
//
//   - ITEM only. FCR-010 settled that P0 enables one scope and shows DAY and
//     TRIP as disabled `준비 중` with "요청 0건". They are rendered so the user
//     can see what is coming, and they are `disabled` so there is nothing to
//     press — the submit builds a CreateItemOptimizationRequest and nothing
//     else, so a DAY body has no path to the wire.
//   - Preview only. The contract calls this a "preview-only optimization run"
//     and says a failure "never mutates the trip". Nothing here writes to the
//     trip cache; an itinerary changes on APPLY and nowhere else
//     (invariants 3 and 4).
//
// MOCK DATA: createOptimization does have an approved example, so the request
// shape below matches it rather than being invented. The run it returns is
// served by an msw handler until BA-050 lands.

/** The scopes the contract defines, in the order the frame lists them. */
const SCOPES: OptimizationScope[] = ['ITEM', 'DAY', 'TRIP'];

export function OptimizeSetupScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { tripId = null } = useParams<{ tripId: string }>();
  const trip = useTrip(tripId);
  const create = useCreateOptimization(tripId);

  const [targetItemId, setTargetItemId] = useState<string | null>(null);
  const [includeCandidates, setIncludeCandidates] = useState(false);
  const [missingTarget, setMissingTarget] = useState(false);

  const detail = trip.data?.trip;
  const etag = trip.data?.etag ?? null;
  const items = detail?.days.flatMap((day) => day.items) ?? [];

  function back() {
    void navigate(tripId === null ? '/feed' : `/trip/${tripId}`);
  }

  function submit() {
    if (detail === undefined) return;
    if (targetItemId === null) {
      // Stated rather than silently ignored: the contract requires
      // targetItemId, so there is nothing to send until one is chosen.
      setMissingTarget(true);
      return;
    }
    setMissingTarget(false);
    create.mutate(
      {
        request: {
          // Always ITEM. The other two scopes have no branch here at all,
          // which is what makes "요청 0건" a property of the code rather than
          // a promise about the UI.
          scope: 'ITEM',
          targetItemId,
          // What the run is computed against, taken from the trip we loaded.
          inputTripVersion: detail.version,
          includeCandidates,
        },
        etag,
        // Minted per attempt by the caller, so a retry of THIS submit replays
        // the same run rather than queuing a second one (invariant 6).
        idempotencyKey: crypto.randomUUID(),
      },
      {
        onSuccess: (run) => {
          void navigate(`/trip/${detail.id}/optimizations/${run.id}`);
        },
      },
    );
  }

  if (trip.isPending) {
    return (
      <section aria-labelledby="optimize-heading" className={styles.screen}>
        <NavBar backLabel={t('optimize.back')} onBack={back} />
        <h1 className={styles.title} id="optimize-heading">
          {t('optimize.title')}
        </h1>
        <p className={styles.state} role="status">
          {t('trip.loading')}
        </p>
      </section>
    );
  }

  if (trip.isError || detail === undefined) {
    return (
      <section aria-labelledby="optimize-heading" className={styles.screen}>
        <NavBar backLabel={t('optimize.back')} onBack={back} />
        <h1 className={styles.title} id="optimize-heading">
          {t('optimize.title')}
        </h1>
        <p className={styles.state} role="alert">
          {t('trip.error')}
        </p>
        <button
          className={styles.retry}
          onClick={() => {
            void trip.refetch();
          }}
          type="button"
        >
          {t('optimize.retry')}
        </button>
      </section>
    );
  }

  const problem = isProblem(create.error) ? create.error : null;
  const failure =
    problem?.code === 'TRIP_CHANGED'
      ? t('optimize.conflict')
      : problem?.code === 'LOCK_CONFLICT'
        ? t('optimize.locked')
        : create.isError
          ? t('optimize.failed')
          : null;

  return (
    <section aria-labelledby="optimize-heading" className={styles.screen}>
      <NavBar backLabel={t('optimize.back')} onBack={back} />

      <h1 className={styles.title} id="optimize-heading">
        {t('optimize.title')}
      </h1>

      <fieldset className={styles.group}>
        <legend className={styles.legend}>{t('optimize.scope')}</legend>
        <div className={styles.chips}>
          {SCOPES.map((scope) => {
            const supported = scope === 'ITEM';
            return (
              <Chip
                disabled={!supported}
                disabledReason={supported ? undefined : t('optimize.scope.comingSoon')}
                key={scope}
                label={t(`optimize.scope.${scope}` as never)}
                selected={supported}
              />
            );
          })}
        </div>
        {/* Named once rather than on each chip: two identical badges beside
            each other read as two different states. */}
        <p className={styles.note}>{t('optimize.scope.comingSoon')}</p>
      </fieldset>

      <fieldset className={styles.group}>
        <legend className={styles.legend}>{t('optimize.target')}</legend>
        {items.length === 0 ? (
          <p className={styles.state}>{t('optimize.targetEmpty')}</p>
        ) : (
          <ul className={styles.items}>
            {items.map((item) => (
              <li key={item.id}>
                <button
                  aria-pressed={targetItemId === item.id}
                  className={styles.item}
                  data-selected={targetItemId === item.id || undefined}
                  onClick={() => {
                    setTargetItemId(item.id);
                    setMissingTarget(false);
                  }}
                  type="button"
                >
                  <span className={styles.itemName}>{item.place.name}</span>
                  <span className={styles.itemMeta}>
                    {formatTime(item.startTime, locale) ?? t('trip.timeUnset')}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </fieldset>

      <label className={styles.toggle}>
        <input
          checked={includeCandidates}
          onChange={(event) => {
            setIncludeCandidates(event.target.checked);
          }}
          type="checkbox"
        />
        <span>{t('optimize.includeCandidates')}</span>
      </label>

      {/* Invariant 3, said plainly. A user pressing this is asking for
          suggestions, not for their itinerary to change. */}
      <p className={styles.note}>{t('optimize.previewNote')}</p>

      {missingTarget ? (
        <p className={styles.state} role="alert">
          {t('optimize.needTarget')}
        </p>
      ) : null}

      {failure === null ? null : (
        <p className={styles.state} role="alert">
          {failure}
        </p>
      )}

      <button
        className={styles.submit}
        disabled={create.isPending || items.length === 0 || etag === null}
        onClick={submit}
        type="button"
      >
        {create.isPending ? t('optimize.submitting') : t('optimize.submit')}
      </button>
    </section>
  );
}
