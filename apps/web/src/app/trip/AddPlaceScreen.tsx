import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  useAddTripCandidate,
  useAddTripItem,
  usePlaceSearch,
  useTrip,
} from '../../shared/api/index.js';
import { Chip, DataAttribution, NavBar, SearchField } from '../../shared/ui/index.js';
import styles from './AddPlaceScreen.module.css';
import { type AddTarget, addTargets, alreadyOnDay, planAdd } from './add-place.js';

// Figma: S07-3 place search `476:3409` (FR-ITM-01, FE-305).
//
// The day chips are the whole design of this screen. Choosing a day and
// choosing 미정 are not two settings of one action — they reach different
// resources:
//
//   a day  → addTripItem      → a TripItem on the itinerary
//   미정    → addTripCandidate → a TripCandidate, no date, schedule untouched
//
// Invariants 1 and 2 are exactly that separation, so the choice is resolved by
// add-place.ts into a plan whose shape makes the wrong endpoint unreachable: a
// candidate plan carries no date to hand to addTripItem.
//
// MOCK DATA: searchPlaces and addTripItem have no approved example (BA-022,
// BA-040). The screen calls the real generated client.
//
// NOT BUILT: the frame shows a crowd reading and a 교체 action per result.
// Crowd is a dated series per place (getPlaceCrowdForecast) rather than a
// scalar on PlaceSummary — FCR-029, still open on #105. Replace is FR-ITM-08
// and needs the comparison sheet (`414:2347`), which is the next slice.

export function AddPlaceScreen() {
  const { tripId } = useParams();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [target, setTarget] = useState<AddTarget>(null);
  const [status, setStatus] = useState<string | null>(null);

  const trip = useTrip(tripId ?? null);
  const search = usePlaceSearch(query);
  const addItem = useAddTripItem(tripId ?? null);
  const addCandidate = useAddTripCandidate(tripId ?? null);

  const days = trip.data?.trip.days ?? [];
  const targets = addTargets(days);
  const busy = addItem.isPending || addCandidate.isPending;

  function dayLabel(date: AddTarget) {
    if (date === null) return t('addPlace.someday');
    const index = days.findIndex((day) => day.date === date);
    return t('trip.day', { n: index + 1 });
  }

  function add(placeId: string, name: string) {
    setStatus(null);
    const plan = planAdd(days, target);
    // One key per user action, reused if this same press is retried.
    const idempotencyKey = crypto.randomUUID();

    if (plan.kind === 'candidate') {
      addCandidate.mutate(
        { request: { placeId, source: { type: 'SEARCH' } }, idempotencyKey },
        {
          onSuccess: (result) => {
            setStatus(
              result.duplicate
                ? t('addPlace.duplicate', { name })
                : t('addPlace.addedCandidate', { name }),
            );
          },
          onError: () => {
            setStatus(t('addPlace.failed'));
          },
        },
      );
      return;
    }

    addItem.mutate(
      {
        item: { placeId, date: plan.date, position: plan.position },
        etag: trip.data?.etag ?? null,
      },
      {
        onSuccess: () => {
          setStatus(t('addPlace.addedItem', { name, day: dayLabel(plan.date) }));
        },
        onError: (error) => {
          setStatus(
            isProblem(error) && error.code === 'TRIP_CHANGED'
              ? t('trip.conflict')
              : t('addPlace.failed'),
          );
          if (isProblem(error) && error.code === 'TRIP_CHANGED') void trip.refetch();
        },
      },
    );
  }

  const results = search.data?.items ?? [];

  return (
    <section className={styles.screen} aria-labelledby="add-place-heading">
      <NavBar
        backLabel={t('addPlace.back')}
        onBack={() => {
          void navigate(`/trip/${tripId ?? ''}`);
        }}
      />
      <h1 className={styles.title} id="add-place-heading">
        {t('addPlace.title')}
      </h1>

      <SearchField
        label={t('addPlace.searchLabel')}
        onChange={(event) => {
          setQuery(event.target.value);
        }}
        placeholder={t('addPlace.search')}
        value={query}
      />

      <div className={styles.dayRow}>
        <span className={styles.dayLabel} id="add-place-day">
          {t('addPlace.day')}
        </span>
        <ul aria-labelledby="add-place-day" className={styles.dayChips}>
          {targets.map((date) => (
            <li key={date ?? 'someday'}>
              <Chip
                label={dayLabel(date)}
                onClick={() => {
                  setTarget(date);
                }}
                selected={target === date}
                size="sm"
              />
            </li>
          ))}
        </ul>
      </div>

      <p className={styles.sectionHead}>{t('addPlace.results')}</p>

      {search.isPending && query.trim().length > 0 ? (
        <p className={styles.state} role="status">
          {t('addPlace.searching')}
        </p>
      ) : null}

      {search.isError ? (
        <p className={styles.state} role="alert">
          {t('addPlace.searchError')}
        </p>
      ) : null}

      {search.isSuccess && results.length === 0 ? (
        <p className={styles.state}>{t('addPlace.noResults')}</p>
      ) : null}

      {results.length > 0 ? (
        <ul className={styles.results}>
          {results.map((place) => {
            const taken = alreadyOnDay(days, place.id, target);
            const meta = [place.categoryName, place.regionName, place.address]
              .filter(
                (part): part is string => typeof part === 'string' && part.length > 0,
              )
              .join(' · ');
            return (
              <li className={styles.result} key={place.id}>
                {place.thumbnailUrl ? (
                  <img
                    alt=""
                    className={styles.thumb}
                    height={44}
                    src={place.thumbnailUrl}
                    width={44}
                  />
                ) : (
                  <span aria-hidden="true" className={styles.thumb} />
                )}
                <span className={styles.resultText}>
                  <span className={styles.name}>{place.name}</span>
                  {meta === '' ? null : <span className={styles.meta}>{meta}</span>}
                  {place.sourceAttribution ? (
                    <DataAttribution compact provenance={place.sourceAttribution} />
                  ) : null}
                </span>
                <button
                  // Named for the place: a column of identical "추가" buttons
                  // tells a screen reader nothing about which one it presses.
                  aria-label={t('addPlace.addNamed', { name: place.name })}
                  className={styles.add}
                  disabled={busy || taken}
                  onClick={() => {
                    add(place.id, place.name);
                  }}
                  title={
                    taken ? t('addPlace.onDay', { day: dayLabel(target) }) : undefined
                  }
                  type="button"
                >
                  {t('addPlace.add')}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}

      {/* The frame's own footnote, and the only place the two destinations are
          spelled out for the user. */}
      <p className={styles.note}>{t('addPlace.note')}</p>

      <p aria-live="polite" className={styles.state} role="status">
        {busy ? t('addPlace.adding') : (status ?? '')}
      </p>
    </section>
  );
}
