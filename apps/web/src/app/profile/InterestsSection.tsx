import { useEffect, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  isProblem,
  useReplaceTripInterests,
  useTrip,
  useTrips,
} from '../../shared/api/index.js';
import { Chip } from '../../shared/ui/components/index.js';
import styles from './InterestsSection.module.css';
import {
  INTEREST_GROUPS,
  MAX_INTERESTS,
  canAdd,
  isDirty,
  toReplaceBody,
  toSelection,
  unknownCodes,
} from './interests.js';

// FR-PRO-05 — view and replace a trip's interest set, on the S14 profile card
// `422:2925` ("여행별 관심사").
//
// MOCK DATA: getTrip and replaceTripInterests have no approved example yet, so
// the msw handlers behind them are schema-valid guesses (BA-031). The screen
// calls the real generated client, so when BA-031 lands only the handlers and
// the fixtures go.
//
// The concurrency behaviour is the point of this card, not a detail:
// replaceTripInterests requires If-Match, and a 409 means someone else changed
// the trip since it was read. The rule this component follows is that a
// conflict never silently discards the user's edit. It shows both ways out —
// take the server's version, or keep editing — and lets the user pick. Auto
// -reloading here would erase whatever they had just selected.

export function InterestsSection() {
  const { t } = useI18n();
  const trips = useTrips();
  const [tripId, setTripId] = useState<string | null>(null);

  // Default to the first trip once the list arrives, without overriding a
  // choice the user has already made.
  const firstTripId = trips.data?.items[0]?.id ?? null;
  useEffect(() => {
    setTripId((current) => current ?? firstTripId);
  }, [firstTripId]);

  const trip = useTrip(tripId);
  const replace = useReplaceTripInterests(tripId);

  // The edit buffer. Feature-local per CLAUDE.md: server state stays in the
  // query cache and only the in-progress selection lives here.
  const [selection, setSelection] = useState<string[] | null>(null);
  const [conflict, setConflict] = useState(false);

  const saved = trip.data?.trip.interests ?? [];
  const current = selection ?? toSelection(saved);
  const dirty = selection !== null && isDirty(current, saved);
  const unknown = unknownCodes(saved);

  // A different trip is a different edit buffer.
  useEffect(() => {
    setSelection(null);
    setConflict(false);
  }, [tripId]);

  function onToggle(code: string) {
    setSelection((previous) => {
      const base = previous ?? toSelection(saved);
      if (base.includes(code)) return base.filter((c) => c !== code);
      if (base.length >= MAX_INTERESTS) return base;
      return [...base, code];
    });
  }

  function onSave() {
    setConflict(false);
    replace.mutate(
      {
        interests: toReplaceBody(current, saved).interests,
        etag: trip.data?.etag ?? null,
      },
      {
        onSuccess: () => {
          // The server's response is now the saved state, so the buffer is
          // dropped and the card reads from the cache again.
          setSelection(null);
        },
        onError: (error) => {
          // TRIP_CHANGED is recoverable and gets its own affordances. Every
          // other failure falls through to the generic message below, and in
          // both cases the selection stays exactly as the user left it.
          if (isProblem(error) && error.code === 'TRIP_CHANGED') setConflict(true);
        },
      },
    );
  }

  const tripItems = trips.data?.items ?? [];

  return (
    <section className={styles.section} aria-labelledby="interests-heading">
      <span className={styles.title} id="interests-heading">
        {t('profile.interests.title')}
      </span>
      <p className={styles.note}>{t('profile.interests.note')}</p>

      {trips.isSuccess && tripItems.length === 0 ? (
        <p className={styles.state}>{t('profile.interests.noTrips')}</p>
      ) : null}

      {tripItems.length > 0 ? (
        <p className={styles.field}>
          <label className={styles.label} htmlFor="interests-trip">
            {t('profile.interests.pickTrip')}
          </label>
          <select
            className={styles.select}
            id="interests-trip"
            value={tripId ?? ''}
            onChange={(event) => {
              setTripId(event.target.value);
            }}
          >
            {tripItems.map((item) => (
              <option key={item.id} value={item.id}>
                {item.title}
              </option>
            ))}
          </select>
        </p>
      ) : null}

      {/* Covers both waits, not just the second one. The trip list resolves
          before a trip can be read, so keying this on `trip.isPending` alone
          leaves the card blank for the whole first request. */}
      {trips.isPending || (trip.isPending && tripId !== null) ? (
        <p className={styles.state} role="status">
          {t('profile.interests.loading')}
        </p>
      ) : null}

      {trip.isError ? (
        <p className={styles.state} role="alert">
          {t('profile.interests.error')}
          <button
            type="button"
            className={styles.retry}
            onClick={() => {
              void trip.refetch();
            }}
          >
            {t('profile.retry')}
          </button>
        </p>
      ) : null}

      {trip.isSuccess ? (
        <>
          {INTEREST_GROUPS.map((group) => (
            <fieldset className={styles.group} key={group.id}>
              <legend className={styles.legend}>
                {t(`wizard.interests.${group.id}` as MessageKey)}
              </legend>
              <ul className={styles.chips}>
                {group.codes.map((code) => {
                  const on = current.includes(code);
                  return (
                    <li key={code}>
                      <Chip
                        label={t(`interest.${code}` as MessageKey)}
                        selected={on}
                        // Only the cap blocks a press, and only for codes that
                        // would add. Deselecting always stays available, or the
                        // user could reach 20 and be unable to get back out.
                        disabled={!on && !canAdd(current)}
                        disabledReason={t('profile.interests.max')}
                        onClick={() => {
                          onToggle(code);
                        }}
                      />
                    </li>
                  );
                })}
              </ul>
            </fieldset>
          ))}

          {/* Codes the server holds that this build has no label for. Shown as
              plain text, not chips: they are kept on save but cannot be
              meaningfully toggled without knowing what they mean. */}
          {unknown.length > 0 ? (
            <p className={styles.state}>
              {t('profile.interests.unknown')}
              <span className={styles.unknownCodes}>{unknown.join(', ')}</span>
            </p>
          ) : null}

          {current.length === 0 ? (
            <p className={styles.state}>{t('profile.interests.empty')}</p>
          ) : null}

          {!canAdd(current) ? (
            <p className={styles.state} role="status">
              {t('profile.interests.max')}
            </p>
          ) : null}

          {conflict ? (
            <div className={styles.conflict} role="alert">
              <p className={styles.conflictText}>{t('profile.interests.conflict')}</p>
              <div className={styles.conflictActions}>
                <button
                  className={styles.retry}
                  onClick={() => {
                    // Take the server's version and drop the local edit, but
                    // only because the user asked for it.
                    setSelection(null);
                    setConflict(false);
                    void trip.refetch();
                  }}
                  type="button"
                >
                  {t('profile.interests.conflict.discard')}
                </button>
                <button
                  className={styles.retry}
                  onClick={() => {
                    // Re-read to pick up the new ETag while keeping the
                    // selection, so the next save can succeed.
                    setConflict(false);
                    void trip.refetch();
                  }}
                  type="button"
                >
                  {t('profile.interests.conflict.reload')}
                </button>
              </div>
            </div>
          ) : null}

          {replace.isError && !conflict ? (
            <p className={styles.state} role="alert">
              {t('profile.interests.error')}
            </p>
          ) : null}

          {replace.isSuccess && !dirty ? (
            <p className={styles.state} role="status">
              {t('profile.interests.saved')}
            </p>
          ) : null}

          <button
            className={styles.save}
            disabled={!dirty || replace.isPending || trip.data.etag === null}
            onClick={onSave}
            type="button"
          >
            {replace.isPending
              ? t('profile.interests.saving')
              : t('profile.interests.save')}
          </button>
        </>
      ) : null}
    </section>
  );
}
