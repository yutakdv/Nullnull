import { useState } from 'react';
import { Link } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import {
  isProblem,
  useDeleteTrip,
  useOptimizationHistory,
  useTrips,
} from '../../shared/api/index.js';
import { ConfirmDialog } from '../../shared/ui/components/index.js';
import { DeletionSection } from './DeletionSection.js';
import { hasResult, rowState, runHref } from './history.js';
import { InterestsSection } from './InterestsSection.js';
import styles from './ProfileScreen.module.css';

// Figma: S14 profile `422:2925`.
//
// FR-PRO-01 guest shell, FR-PRO-02 disabled sign-in, FR-PRO-03 trip list.
//
// MOCK DATA: the trip list and the optimization history are served by msw
// fixtures because listTrips and listOptimizationHistory have no approved
// example yet (packages/contracts/src/index.ts). The screen calls the real
// generated client, so when BA-030 and BA-053 land only the handlers and the
// fixtures are removed — nothing here changes.
//
// The history section shows status, date and target trip only. Itinerary
// content is deliberately absent: CLAUDE.md forbids duplicating a schedule for
// history, and the contract does not return one.

/** Formats a run's date the way the Figma rows read: "10/2". */
function runDate(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { month: 'numeric', day: 'numeric' }).format(
    new Date(iso),
  );
}

function tripDates(start: string, end: string, locale: string): string {
  const format = new Intl.DateTimeFormat(locale, { month: 'numeric', day: 'numeric' });
  return `${format.format(new Date(start))} – ${format.format(new Date(end))}`;
}

/** The trip a confirm is open for, with the key that deletion will carry. */
interface PendingDelete {
  id: string;
  title: string;
  version: number;
  idempotencyKey: string;
}

export function ProfileScreen() {
  const { locale, t } = useI18n();
  const trips = useTrips();
  const history = useOptimizationHistory();
  const remove = useDeleteTrip();
  const [pending, setPending] = useState<PendingDelete | null>(null);
  // Announced at screen level, not in the row: a successful delete unmounts
  // the row, which would destroy the message reporting it. RemoveItemControl
  // learned the same thing.
  const [announced, setAnnounced] = useState<string | null>(null);
  const [failed, setFailed] = useState<'conflict' | 'failed' | null>(null);

  function confirmDelete() {
    if (!pending) return;
    const { id, title, version, idempotencyKey } = pending;
    setPending(null);
    setFailed(null);
    setAnnounced(null);
    remove.mutate(
      // The contract's ETag is the quoted trip version, so the list row
      // already holds a valid If-Match and no extra getTrip is needed.
      { tripId: id, etag: `"${String(version)}"`, idempotencyKey },
      {
        onSuccess: () => {
          setAnnounced(t('trip.delete.deleted', { name: title }));
        },
        onError: (error) => {
          // A conflict means this trip changed elsewhere, so the version the
          // row was holding is stale. Nothing was deleted; refetching the list
          // is what makes a second attempt able to succeed.
          if (isProblem(error) && error.code === 'TRIP_CHANGED') {
            setFailed('conflict');
            void trips.refetch();
            return;
          }
          setFailed('failed');
        },
      },
    );
  }

  return (
    <section className={styles.screen} aria-labelledby="profile-heading">
      {/* No NavBar: this is a tab destination with no back control, the h1
          below is the title the frame shows, and the settings glyph beside it
          has no P0 destination — a control that goes nowhere is worse than an
          absent one. An empty bar would be a spacer pretending to be chrome. */}
      <h1 className={styles.title} id="profile-heading">
        {t('profile.title')}
      </h1>

      <div className={styles.card}>
        <div className={styles.guest}>
          {/* 422:2934: a 44px avatar. Decorative — the name beside it is the
              content, so it carries no alternative text of its own. */}
          <span aria-hidden="true" className={styles.avatar} />
          <span className={styles.guestText}>
            <span className={styles.guestName}>{t('profile.guest.name')}</span>
            <span className={styles.guestNote}>{t('profile.guest.note')}</span>
          </span>
        </div>
        {/* Now a link, where P0 had inert text and a `준비 중` badge (FCR-006).
            The owner moved sign-in into P0 (#264, #265), so this row has a
            destination.

            It still sends nothing. /sign-in has no contract behind it — there
            is no auth operation in docs/api/openapi.yaml — so the screen it
            opens tells the traveller that rather than posting to a guessed
            path. profile.test.tsx keeps asserting no auth request leaves the
            app, and that assertion is what holds this line honest. */}
        <p className={styles.loginRow}>
          <Link className={styles.loginLabel} to="/sign-in">
            {t('profile.login')}
          </Link>
        </p>
      </div>

      {/* Labelled section, not a bare div: the trip list and the history list
          both render links titled after a trip, so without a name on each
          group a screen reader hears two identical sets of links. */}
      <section aria-labelledby="profile-trips-heading" className={styles.card}>
        <div className={styles.sectionRow}>
          <h2 className={styles.sectionHead} id="profile-trips-heading">
            {t('profile.trips.title')}
          </h2>
          {/* 422:2943: the count sits at the end of the section row.
              Shown only while this page IS the whole set. `TripPage` has no
              total — `items.length` counts one page — so past the first page
              the number would be smaller than the list it labels, with nothing
              on screen to reveal the gap. `hasMore` is the contract's own way
              of saying the page is partial, and suppressing the figure there
              is the same rule the rest of this app follows: a number the
              contract cannot source is not rendered. */}
          {trips.isSuccess && !trips.data.page.hasMore ? (
            <span className={styles.rowValue}>
              {t('profile.trips.count', { count: trips.data.items.length })}
            </span>
          ) : null}
        </div>
        {trips.isPending ? (
          <p className={styles.state} role="status">
            {t('profile.trips.loading')}
          </p>
        ) : null}
        {trips.isError ? (
          <p className={styles.state} role="alert">
            {t('profile.trips.error')}
            <button
              type="button"
              className={styles.retry}
              onClick={() => {
                void trips.refetch();
              }}
            >
              {t('profile.retry')}
            </button>
          </p>
        ) : null}
        {trips.isSuccess && trips.data.items.length === 0 ? (
          <p className={styles.state}>{t('profile.trips.empty')}</p>
        ) : null}
        {trips.isSuccess && trips.data.items.length > 0 ? (
          <ul className={styles.rows}>
            {trips.data.items.map((trip) => (
              <li className={styles.tripRow} key={trip.id}>
                <Link className={styles.row} to={`/trip/${trip.id}`}>
                  <span className={styles.rowText}>
                    <span className={styles.rowTitle}>{trip.title}</span>
                    <span className={styles.rowNote}>
                      {tripDates(trip.startDate, trip.endDate, locale)}
                    </span>
                  </span>
                  <span className={styles.rowValue} aria-hidden="true">
                    ›
                  </span>
                </Link>
                {/* After the link, never inside it: an interactive control
                    cannot nest in an anchor, and the profile's keyboard test
                    tabs once expecting the trip link to take focus first.
                    Opening the trip is the ordinary action; deleting it is
                    not, so it does not come first in the tab order either. */}
                <button
                  aria-label={t('trip.delete.open', { name: trip.title })}
                  className={styles.rowDelete}
                  disabled={remove.isPending}
                  onClick={() => {
                    // The key is minted HERE, when the user opens the confirm,
                    // and held until that deletion ends. Minting it at send
                    // time would give a retry of the same confirmed deletion a
                    // fresh key, which is a second destructive command rather
                    // than a retry of the first.
                    setPending({
                      id: trip.id,
                      title: trip.title,
                      version: trip.version,
                      idempotencyKey: crypto.randomUUID(),
                    });
                  }}
                  type="button"
                >
                  {/* A glyph, with the trip's name carried by aria-label
                      above. The full label as visible text made the button
                      205–226px wide, which forced the page to scroll
                      sideways at 200% zoom (WCAG 1.4.10) — the reflow spec
                      caught it. Eleven other row controls in this app take
                      the same shape. */}
                  <span aria-hidden="true">✕</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}

        {/* Outside the list, so it outlives the row a successful delete
            removes. */}
        {announced === null ? null : (
          <p aria-live="polite" className={styles.state} role="status">
            {announced}
          </p>
        )}
        {failed === null ? null : (
          <p className={styles.state} role="alert">
            {t(failed === 'conflict' ? 'trip.delete.conflict' : 'trip.delete.failed')}
          </p>
        )}

        <ConfirmDialog
          open={pending !== null}
          title={t('trip.delete.title')}
          body={t('trip.delete.body', { name: pending?.title ?? '' })}
          confirmLabel={t('trip.delete.confirm')}
          cancelLabel={t('trip.delete.cancel')}
          destructive
          onConfirm={confirmDelete}
          onCancel={() => {
            setPending(null);
          }}
        />
      </section>

      <section aria-labelledby="profile-history-heading" className={styles.card}>
        <h2 className={styles.sectionHead} id="profile-history-heading">
          {t('profile.history.title')}
        </h2>
        {history.isPending ? (
          <p className={styles.state} role="status">
            {t('profile.history.loading')}
          </p>
        ) : null}
        {history.isError ? (
          <p className={styles.state} role="alert">
            {t('profile.history.error')}
            <button
              type="button"
              className={styles.retry}
              onClick={() => {
                void history.refetch();
              }}
            >
              {t('profile.retry')}
            </button>
          </p>
        ) : null}
        {history.isSuccess && history.data.items.length === 0 ? (
          <p className={styles.state}>{t('profile.history.empty')}</p>
        ) : null}
        {history.isSuccess && history.data.items.length > 0 ? (
          <ul className={styles.rows}>
            {history.data.items.map((run) => {
              const state = rowState(run);
              const secondary =
                state.kind === 'decision'
                  ? t(`profile.history.decision.${state.value}` as MessageKey)
                  : state.kind === 'pending'
                    ? t('profile.history.pending')
                    : t(`profile.history.${state.value}` as MessageKey);
              const body = (
                <>
                  <span className={styles.rowText}>
                    <span className={styles.rowTitle}>
                      {runDate(run.queuedAt, locale)} · {run.tripTitle}
                    </span>
                    <span className={styles.rowNote}>
                      {/* Scope first: an APPLIED run means something different
                          for one stop than for the whole trip. */}
                      {t(`profile.history.scope.${run.scope}` as MessageKey)} ·{' '}
                      {secondary}
                    </span>
                  </span>
                  {hasResult(run) ? (
                    <span className={styles.rowValue} aria-hidden="true">
                      ›
                    </span>
                  ) : null}
                </>
              );
              return (
                <li key={run.runId}>
                  {/* A queued or running run has no result to open, so the row
                      states its status instead of linking to nothing. */}
                  {hasResult(run) ? (
                    <Link
                      aria-label={t('profile.history.openRun', {
                        date: runDate(run.queuedAt, locale),
                        trip: run.tripTitle,
                      })}
                      className={styles.row}
                      to={runHref(run)}
                    >
                      {body}
                    </Link>
                  ) : (
                    <span className={styles.row}>{body}</span>
                  )}
                </li>
              );
            })}
          </ul>
        ) : null}
        {/* States it plainly, because the absence is the point. */}
        <p className={styles.note}>{t('profile.history.note')}</p>
      </section>

      {/* FE-106: was an inert row describing the feature; now the feature. */}
      <div className={styles.card}>
        <InterestsSection />
      </div>

      <div className={styles.card}>
        <ul className={styles.rows}>
          <li>
            <Link className={styles.row} to="/about-data">
              <span className={styles.rowText}>
                <span className={styles.rowTitle}>{t('profile.dataGuide.title')}</span>
                <span className={styles.rowNote}>{t('profile.dataGuide.note')}</span>
              </span>
              <span className={styles.rowValue} aria-hidden="true">
                ›
              </span>
            </Link>
          </li>
          <li>
            {/* P0 never asks for geolocation and never sends coordinates, so
                this row reports the state rather than offering a toggle. */}
            <span className={styles.row}>
              <span className={styles.rowText}>
                <span className={styles.rowTitle}>{t('profile.location.title')}</span>
                <span className={styles.rowNote}>{t('profile.location.note')}</span>
              </span>
              <span className={styles.rowValue}>{t('profile.location.off')}</span>
            </span>
          </li>
        </ul>
      </div>

      <DeletionSection />
    </section>
  );
}
