package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read boundary for one immutable crowd snapshot set. It never reaches a provider. */
public interface CrowdForecastQuery {

    Optional<SnapshotSet> latestFresh(UUID placeId, Instant from, Instant to, Instant now);

    Optional<SnapshotSet> latestStale(UUID placeId, Instant from, Instant to, Instant now);

    record SnapshotSet(UUID id, List<Snapshot> snapshots) {
        public SnapshotSet {
            snapshots = List.copyOf(snapshots);
            if (snapshots.isEmpty()) {
                throw new IllegalArgumentException("snapshot set must contain at least one point");
            }
        }
    }

    record Snapshot(UUID id, UUID snapshotSetId, UUID collectorRunId, UUID placeId,
            SourceDescriptor source, SourceState sourceState, Instant observedAt, Instant targetAt,
            Instant fetchedAt, Instant staleAt, String metricCode, BigDecimal value, String unit,
            String ordinalLevel, BigDecimal confidence, Set<QualityFlag> qualityFlags,
            String forecastIssueId, String comparisonGroupId, String normalizationVersion,
            Integer observedAtSkewSeconds, ComparisonScope scope, String scopeLabel, String mappingType,
            boolean fallbackUsed, boolean incidentActive) {
        public Snapshot {
            qualityFlags = Set.copyOf(qualityFlags);
        }
    }

    record SourceDescriptor(String code, String displayName, long registryVersion, String license,
            String officialUrl, String licenseUrl, String attribution, String metricDefinition) {
    }
}
