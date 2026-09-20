package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.SourceState;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence port for one Seoul live-area reading. Raw provider bodies have no path here.
 *
 * <p>The reading is stored with <strong>no value and no stage</strong>, and both absences are the
 * provider's shape rather than an omission:
 *
 * <ul>
 *   <li>{@code value} - Seoul publishes a population MIN/MAX range, which no single number holds,
 *       and the congestion step it publishes beside that is a percentage of THAT AREA'S OWN past
 *       average. A midpoint would be a figure nobody measured and a cross-area comparison the data
 *       cannot support (invariant 8).
 *   <li>{@code ordinal_level} - {@code CrowdStage.SOURCES_WITH_REVIEWED_SCALE} is empty, and its
 *       javadoc says the slice that adds a producer adds its source code there TOGETHER WITH THE
 *       MAPPING THAT WAS REVIEWED. Seoul publishes four steps; the product's scale has five cells
 *       (FCR-035). Which four of the five is a product judgement, not something the source implies,
 *       so it is an owner decision and not a constant invented here. Until it is made the column
 *       stays null - a stored stage from an unreviewed source is served as null with SCHEMA_DRIFT
 *       anyway, which would report a drift that did not happen.
 * </ul>
 *
 * <p>What the row does carry is the part that is unambiguous: which area, at which instant, from
 * which registry revision, and when it stops being current. That is enough for the Live page to
 * distinguish live from stale from absent, which is invariant 6.
 */
public interface SeoulLiveSnapshotStore {

    /** Our code for the one metric this source publishes; the provider has no code of its own. */
    String METRIC_CODE = "SEOUL_LIVE_AREA_CONGESTION_LEVEL";

    /** Bumped when the normalization changes shape, not when the provider changes its schema. */
    String NORMALIZATION_VERSION = "seoul-live-area-v1";

    /** What the reading is about, in the source's own words (SOURCE_CATALOG section 5). */
    String SCOPE_LABEL = "서울 실시간 도시데이터 주요 장소";

    /**
     * The reading, already decided into the state it will be stored as.
     *
     * @param sourceState LIVE, or STALE when it had already expired before we received it
     * @param staleAt when it stops being current, or null when it already had
     */
    record Reading(UUID snapshotSetId, UUID snapshotId, UUID collectorRunId, long sourceRegistryVersion,
            UUID liveAreaId, SourceState sourceState, Instant observedAt, Instant fetchedAt, Instant staleAt) {

        public Reading {
            Objects.requireNonNull(snapshotSetId, "snapshotSetId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(collectorRunId, "collectorRunId");
            Objects.requireNonNull(liveAreaId, "liveAreaId");
            Objects.requireNonNull(sourceState, "sourceState");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(fetchedAt, "fetchedAt");
            if (sourceRegistryVersion < 1) {
                throw new IllegalArgumentException("sourceRegistryVersion must be positive");
            }
            if (sourceState != SourceState.LIVE && sourceState != SourceState.STALE) {
                throw new IllegalArgumentException("a Seoul reading is stored LIVE or STALE");
            }
            if (staleAt != null && !staleAt.isAfter(fetchedAt)) {
                // The same rule crowd_snapshots_staleness_check states. Refusing here rather than at
                // the database makes the branch below the only producer of this shape.
                throw new IllegalArgumentException("staleAt must be after fetchedAt or absent");
            }
        }

        /**
         * Decides the state from the two instants and the source's own stale window.
         *
         * <p><strong>A reading can arrive already expired, and the schema forbids saying so the
         * obvious way.</strong> {@code stale_at = observedAt + staleAfterSeconds} is the honest
         * expiry, but {@code crowd_snapshots_staleness_check} requires {@code stale_at > fetched_at}
         * - so a reading we received more than the window after it was taken cannot carry its real
         * expiry. Dropping it would lose an observation that is still true about the past; moving
         * the expiry forward would claim it is current. It is stored STALE with no expiry instead:
         * the state says what it is, and a null {@code stale_at} says the question of when it
         * expires is already answered.
         */
        public static Reading of(UUID snapshotSetId, UUID snapshotId, UUID collectorRunId,
                long sourceRegistryVersion, UUID liveAreaId, Instant observedAt, Instant fetchedAt,
                long staleAfterSeconds) {
            Instant expiry = Objects.requireNonNull(observedAt, "observedAt").plusSeconds(staleAfterSeconds);
            boolean current = expiry.isAfter(Objects.requireNonNull(fetchedAt, "fetchedAt"));
            return new Reading(snapshotSetId, snapshotId, collectorRunId, sourceRegistryVersion, liveAreaId,
                    current ? SourceState.LIVE : SourceState.STALE, observedAt, fetchedAt,
                    current ? expiry : null);
        }
    }

    /** One snapshot set holding one reading, in one transaction. */
    void save(Reading reading);
}
