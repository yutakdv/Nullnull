import { useState } from 'react';
import { Link, Navigate, useNavigate, useOutletContext } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import { useTrips, useUpdatePreferences } from '../../shared/api/index.js';
import { formatTripPeriod } from '../../shared/i18n/trip-period.js';
import {
  IconCheck,
  IconChevronRight,
  IconPinVisitFilled,
  IconPlus,
  IconTrip,
} from '../../shared/ui/icons/index.js';
import type { AppShellOutletContext } from '../AppShell.js';
import styles from './TripSelectScreen.module.css';

/** The 내 여행 index: choose the trip used for the feed, then open its detail. */
export function TripSelectScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const { activeTripId, setActiveTripId } = useOutletContext<AppShellOutletContext>();
  const trips = useTrips();
  const updatePreferences = useUpdatePreferences();
  const [selectionRejected, setSelectionRejected] = useState(false);

  // An empty owner needs a trip to select, so continue into the existing setup
  // flow instead of showing an empty chooser with nowhere useful to go.
  if (trips.isSuccess && trips.data.items.length === 0) {
    return <Navigate replace to="/start" />;
  }

  function chooseTrip(tripId: string) {
    setSelectionRejected(false);
    updatePreferences.reset();
    updatePreferences.mutate(
      { activeTripId: tripId },
      {
        onSuccess: (owner) => {
          if (!owner.activeTripId) {
            setSelectionRejected(true);
            return;
          }
          setActiveTripId(owner.activeTripId);
          void navigate(`/trip/${owner.activeTripId}`);
        },
      },
    );
  }

  return (
    <section aria-labelledby="trip-select-heading" className={styles.screen}>
      <header className={styles.header}>
        <h1 className={styles.title} id="trip-select-heading">
          {t('tripSelect.title')}
        </h1>
        <p className={styles.lead}>{t('tripSelect.lead')}</p>
      </header>

      <div className={styles.card}>
        {trips.isPending ? (
          <p className={styles.state} role="status">
            {t('tripSelect.loading')}
          </p>
        ) : null}

        {trips.isError ? (
          <p className={styles.state} role="alert">
            <span>{t('tripSelect.error')}</span>
            <button
              className={styles.retry}
              onClick={() => {
                void trips.refetch();
              }}
              type="button"
            >
              {t('tripSelect.retry')}
            </button>
          </p>
        ) : null}

        {trips.isSuccess && trips.data.items.length > 0 ? (
          <ul aria-busy={updatePreferences.isPending} className={styles.list}>
            {trips.data.items.map((trip) => {
              const isRepresentative = trip.id === activeTripId;
              return (
                <li key={trip.id}>
                  <button
                    aria-pressed={isRepresentative}
                    className={styles.trip}
                    data-active={isRepresentative || undefined}
                    disabled={updatePreferences.isPending}
                    onClick={() => {
                      chooseTrip(trip.id);
                    }}
                    type="button"
                  >
                    <span aria-hidden="true" className={styles.thumbnail}>
                      <IconTrip size={34} />
                    </span>
                    <span className={styles.tripInfo}>
                      <span className={styles.tripHead}>
                        <span className={styles.tripName}>{trip.title}</span>
                        {isRepresentative ? (
                          <span className={styles.representative}>
                            <IconCheck size={14} />
                            {t('tripSelect.representative')}
                          </span>
                        ) : null}
                      </span>
                      <span className={styles.tripDates}>
                        {formatTripPeriod(trip.startDate, trip.endDate, locale, 'short')}
                      </span>
                      <span className={styles.tripCandidates}>
                        <IconPinVisitFilled size={16} />
                        {t('tripSelect.candidates', { count: trip.candidateCount })}
                      </span>
                    </span>
                    <span aria-hidden="true" className={styles.arrow}>
                      <IconChevronRight size={20} />
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : null}
      </div>

      {updatePreferences.isPending ? (
        <p className={styles.srOnly} role="status">
          {t('tripSelect.saving')}
        </p>
      ) : null}

      <Link
        aria-disabled={updatePreferences.isPending || undefined}
        className={styles.createTrip}
        onClick={(event) => {
          if (updatePreferences.isPending) event.preventDefault();
        }}
        to="/start"
      >
        <span aria-hidden="true" className={styles.createIcon}>
          <IconPlus size={22} />
        </span>
        <span className={styles.createLabel}>{t('tripSelect.create')}</span>
        <span aria-hidden="true" className={styles.createArrow}>
          <IconChevronRight size={20} />
        </span>
      </Link>

      {updatePreferences.isError || selectionRejected ? (
        <p className={styles.error} role="alert">
          {t('tripSelect.saveError')}
        </p>
      ) : null}
    </section>
  );
}
