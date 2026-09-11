import { Link } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { useOptimizationHistory, useTrips } from '../../shared/api/index.js';
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

export function ProfileScreen() {
  const { locale, t } = useI18n();
  const trips = useTrips();
  const history = useOptimizationHistory();

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
        {/* P0 has no accounts. Rendered as inert text with a `준비 중` badge —
            never a control, so there is nothing to press and no request. */}
        <p className={styles.loginRow}>
          <span className={styles.loginLabel}>{t('profile.login')}</span>
          <span className={styles.badge}>{t('profile.comingSoon')}</span>
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
          {/* 422:2943: the count sits at the end of the section row. */}
          {trips.isSuccess ? (
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
              <li key={trip.id}>
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
              </li>
            ))}
          </ul>
        ) : null}
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
