import { useEffect, useId, useRef, useState } from 'react';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import type { MessageKey } from '../../i18n/messages.js';
import { PlaceAttribution } from '../../shared/ui/index.js';
import styles from './ReplaceSheet.module.css';
import {
  alternatives,
  comparisonBlock,
  hasAlternatives,
  replaceLockEffect,
} from './replace.js';

// Compare-and-replace sheet, S07-11 `479:3497` / `414:2347`
// (FR-ITM-07, FR-ITM-08, FE-305).
//
// This is the screen invariant 8 is about: it shows the current place beside an
// alternative, which is the comparison the invariant restricts. So the rules
// live in replace.ts and this component only renders what they allow —
//
//   - No delta, no "less crowded", no ranking. Each side shows its own reading
//     with its own provenance, and when the pair is not comparable the sheet
//     SAYS so instead of leaving the two numbers to imply a winner.
//   - The alternatives keep the server's order. Sorting by crowd would be the
//     same numeric comparison by another name.
//
// The lock consequence is computed from the item, not written into copy:
// swapping the place releases MUST_VISIT (it pins the place) and keeps DATE,
// TIME and RESERVATION, which replaceTripItem says the item keeps
// unconditionally. That is the frame's own warning, kept true as lock types
// change. See replaceLockEffect for why this rests on the operation
// description rather than on preserveDateTime (#203).

type TripDetail = components['schemas']['TripDetail'];
type TripItem = TripDetail['days'][number]['items'][number];
type RelatedPlace = components['schemas']['RelatedPlace'];
type RelatedPlaceResult = components['schemas']['RelatedPlaceResult'];

export interface ReplaceSheetProps {
  open: boolean;
  item: TripItem;
  result: RelatedPlaceResult | undefined;
  loading: boolean;
  failed: boolean;
  busy: boolean;
  /**
   * The chosen replacement, and the locks the sheet told the user it releases.
   *
   * Typed as `ReleasedPlaceLocks` rather than `ConstraintType[]`: the two hold
   * the same four values today, but the contract says this list narrows to
   * `[MUST_VISIT, RESERVATION]` once #203 settles. Binding to the request
   * schema makes that narrowing a typecheck failure here instead of a 422 at
   * runtime.
   */
  onConfirm: (
    choice: RelatedPlace,
    released: components['schemas']['ReleasedPlaceLocks'],
  ) => void;
  onCancel: () => void;
}

export function ReplaceSheet({
  open,
  item,
  result,
  loading,
  failed,
  busy,
  onConfirm,
  onCancel,
}: ReplaceSheetProps) {
  const { t } = useI18n();
  // Unique per instance, not a literal: this screen mounts one sheet PER trip
  // item, so a hardcoded id put the same value on every dialog in the document.
  // getElementById returns the first match, so every sheet after the first was
  // labelled by another item's heading — a screen reader announced the wrong
  // place. ConfirmDialog already does it this way.
  const titleId = useId();
  const ref = useRef<HTMLDialogElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const [chosen, setChosen] = useState<string | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open) {
      restoreTo.current = document.activeElement as HTMLElement | null;
      if (!dialog.open) dialog.showModal();
      cancelRef.current?.focus();
    } else if (dialog.open) {
      dialog.close();
      setChosen(null);
    }
  }, [open]);

  useEffect(() => {
    if (open) return;
    const target = restoreTo.current;
    restoreTo.current = null;
    if (target?.isConnected) target.focus();
  }, [open]);

  const options = result ? alternatives(result) : [];
  const effect = replaceLockEffect(item);
  const selected = options.find((option) => option.place.id === chosen) ?? null;

  /** What this swap does to the item's locks, named rather than implied. */
  function lockLine(): string {
    const names = (types: typeof effect.released) =>
      types.map((type) => t(`trip.lock.${type}` as MessageKey)).join(' · ');
    if (effect.released.length === 0 && effect.kept.length === 0) {
      return t('replace.keepsNone');
    }
    if (effect.released.length === 0)
      return t('replace.keeps', { locks: names(effect.kept) });
    if (effect.kept.length === 0) {
      return t('replace.releases', { locks: names(effect.released) });
    }
    return `${t('replace.releases', { locks: names(effect.released) })} · ${t('replace.keeps', { locks: names(effect.kept) })}`;
  }

  return (
    <dialog
      aria-labelledby={titleId}
      className={styles.sheet}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      onClick={(event) => {
        if (event.target === ref.current) onCancel();
      }}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return;
        event.preventDefault();
        event.stopPropagation();
        onCancel();
      }}
      ref={ref}
    >
      <div className={styles.panel}>
        <span aria-hidden="true" className={styles.grab} />

        <div className={styles.head}>
          <h2 className={styles.title} id={titleId}>
            {t('replace.title')}
          </h2>
          <button
            className={styles.cancel}
            onClick={onCancel}
            ref={cancelRef}
            type="button"
          >
            {t('replace.cancel')}
          </button>
        </div>

        {/* The stop being replaced, with its own reading and its own source. */}
        <div className={styles.current}>
          <p className={styles.sideLabel}>{t('replace.current')}</p>
          <p className={styles.placeName}>{item.place.name}</p>
          <PlaceAttribution compact place={item.place} />
        </div>

        {loading ? (
          <p className={styles.state} role="status">
            {t('replace.loading')}
          </p>
        ) : null}

        {failed ? (
          <p className={styles.state} role="alert">
            {t('replace.error')}
          </p>
        ) : null}

        {/* Each non-deciding state says its own thing. An empty items array is
            not an answer on its own. */}
        {result && !hasAlternatives(result) && !loading ? (
          <p className={styles.state} role="status">
            {t(
              `replace.state.${result.state === 'EXACT' || result.state === 'SIMILAR' ? 'NONE' : result.state}` as MessageKey,
            )}
          </p>
        ) : null}

        {options.length > 0 ? (
          <>
            <p className={styles.sideLabel}>{t('replace.instead')}</p>
            <ul className={styles.options}>
              {options.map((option) => {
                const block = comparisonBlock(item, option);
                const picked = option.place.id === chosen;
                return (
                  <li key={option.place.id}>
                    <button
                      aria-pressed={picked}
                      className={
                        picked ? `${styles.option} ${styles.picked}` : styles.option
                      }
                      onClick={() => {
                        setChosen(option.place.id);
                      }}
                      type="button"
                    >
                      <span className={styles.optionName}>{option.place.name}</span>
                      <span className={styles.relation}>
                        {t(`replace.relation.${option.relation}` as MessageKey)}
                      </span>
                      {/* The server's own reason, shown as written. */}
                      <span className={styles.reason}>{option.relationReason}</span>
                      {/* Invariant 8: no delta, no ranking. When the pair is
                          not comparable the sheet says so rather than letting
                          two numbers imply a winner. */}
                      <span className={styles.compare}>
                        {block === null
                          ? null
                          : block === 'no-data'
                            ? t('replace.compare.noData')
                            : t('replace.compare.unavailable')}
                      </span>
                      <PlaceAttribution compact place={option.place} />
                    </button>
                  </li>
                );
              })}
            </ul>

            {/* What the swap does to this stop's locks. */}
            <p className={styles.locks}>{lockLine()}</p>

            <button
              className={styles.confirm}
              disabled={selected === null || busy}
              onClick={() => {
                // effect.released is what lockLine() just told the user this
                // swap would release, so it is their answer — not a defensive
                // list of every lock present. The server deletes every lock
                // named here, so sending more than was asked about would
                // release locks the user was never shown.
                if (selected) onConfirm(selected, effect.released);
              }}
              type="button"
            >
              {busy ? t('replace.replacing') : t('replace.confirm')}
            </button>
          </>
        ) : null}
      </div>
    </dialog>
  );
}
