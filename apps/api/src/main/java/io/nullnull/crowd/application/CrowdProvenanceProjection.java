package io.nullnull.crowd.application;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import io.nullnull.crowd.domain.ComparisonScope;
import io.nullnull.crowd.domain.ComparisonVerdict;
import io.nullnull.crowd.domain.CrowdPoint;
import io.nullnull.crowd.domain.QualityFlag;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.crowd.domain.TemporalComparisonPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps a normalized snapshot into the public, explicit data-provenance shape. */
@Component
public class CrowdProvenanceProjection {

    private final TemporalComparisonPolicy comparison = new TemporalComparisonPolicy();

    public CrowdMetric project(CrowdForecastQuery.Snapshot snapshot, Instant now, boolean staleFallback) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(now, "now");
        Freshness freshness = freshness(snapshot, now);
        SourceState responseState = responseState(snapshot.sourceState(), freshness);
        Set<QualityFlag> flags = flags(snapshot);
        UUID provenanceId = responseState == SourceState.UNAVAILABLE ? null : snapshot.id();
        boolean complete = provenanceComplete(snapshot, responseState, provenanceId);
        CrowdPoint point = new CrowdPoint(provenanceId, snapshot.placeId(), snapshot.scope(), snapshot.source().code(),
                responseState, snapshot.metricCode(), snapshot.forecastIssueId(), snapshot.targetAt(), snapshot.value(),
                flags, complete, Math.toIntExact(snapshot.source().registryVersion()), snapshot.normalizationVersion());
        ComparisonVerdict verdict = comparison.eligibility(point);
        String axis = temporalAxis(snapshot, responseState) ? "TEMPORAL" : null;
        DataProvenance provenance = new DataProvenance(snapshot.source().code(), snapshot.source().displayName(),
                snapshot.source().registryVersion(), responseState, snapshot.observedAt(), snapshot.targetAt(),
                freshness.name(), snapshot.fetchedAt(), snapshot.staleAt(), snapshot.confidence(),
                snapshot.source().license(), snapshot.source().officialUrl(), snapshot.source().licenseUrl(),
                snapshot.source().attribution(), null, snapshot.source().metricDefinition(),
                snapshot.normalizationVersion(), flags.stream().map(Enum::name).sorted().toList(),
                snapshot.forecastIssueId(), axis, verdict.eligible(), verdict.reasonCode(),
                snapshot.comparisonGroupId(), snapshot.collectorRunId(), snapshot.snapshotSetId(),
                snapshot.observedAtSkewSeconds(), snapshot.scope().name(), snapshot.scopeLabel(), snapshot.mappingType(),
                snapshot.fallbackUsed() || staleFallback, provenanceId);
        return new CrowdMetric(responseState, snapshot.value(), snapshot.unit(), snapshot.ordinalLevel(),
                label(snapshot, responseState), provenance);
    }

    private static Freshness freshness(CrowdForecastQuery.Snapshot snapshot, Instant now) {
        if (snapshot.sourceState() == SourceState.UNAVAILABLE || snapshot.sourceState() == SourceState.REPLAY
                || snapshot.staleAt() == null) {
            return Freshness.UNKNOWN;
        }
        if (snapshot.sourceState() == SourceState.STALE) {
            return Freshness.STALE;
        }
        return now.isBefore(snapshot.staleAt()) ? Freshness.FRESH : Freshness.STALE;
    }

    private static SourceState responseState(SourceState sourceState, Freshness freshness) {
        if (sourceState == SourceState.FORECAST || sourceState == SourceState.LIVE) {
            return freshness == Freshness.STALE ? SourceState.STALE : sourceState;
        }
        return sourceState;
    }

    private static Set<QualityFlag> flags(CrowdForecastQuery.Snapshot snapshot) {
        EnumSet<QualityFlag> flags = snapshot.qualityFlags().isEmpty()
                ? EnumSet.noneOf(QualityFlag.class) : EnumSet.copyOf(snapshot.qualityFlags());
        if (snapshot.incidentActive()) {
            flags.add(QualityFlag.PROVIDER_INCIDENT);
        }
        return Set.copyOf(flags);
    }

    private static boolean provenanceComplete(CrowdForecastQuery.Snapshot snapshot, SourceState responseState,
            UUID provenanceId) {
        CrowdForecastQuery.SourceDescriptor source = snapshot.source();
        if (provenanceId == null || source == null || blank(source.code()) || blank(source.displayName())
                || source.registryVersion() < 1 || snapshot.snapshotSetId() == null || snapshot.collectorRunId() == null
                || snapshot.placeId() == null || snapshot.fetchedAt() == null || blank(snapshot.metricCode())
                || blank(snapshot.normalizationVersion()) || snapshot.scope() == null || blank(snapshot.scopeLabel())
                || blank(snapshot.mappingType()) || blank(source.metricDefinition()) || blank(source.license())
                || blank(source.officialUrl()) || blank(source.licenseUrl()) || blank(source.attribution())) {
            return false;
        }
        if (responseState == SourceState.FORECAST || responseState == SourceState.STALE) {
            return snapshot.targetAt() != null && !blank(snapshot.forecastIssueId())
                    && !blank(snapshot.comparisonGroupId());
        }
        return true;
    }

    private static boolean temporalAxis(CrowdForecastQuery.Snapshot snapshot, SourceState responseState) {
        return snapshot.scope() == ComparisonScope.PLACE
                && (responseState == SourceState.FORECAST || responseState == SourceState.STALE)
                && snapshot.targetAt() != null;
    }

    private static String label(CrowdForecastQuery.Snapshot snapshot, SourceState responseState) {
        if (responseState == SourceState.STALE) {
            return "만료된 관광지 집중률 예측";
        }
        if (snapshot.sourceState() == SourceState.FORECAST) {
            return "관광지 집중률 예측";
        }
        return snapshot.source().metricDefinition();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Freshness { FRESH, STALE, UNKNOWN }

    public record CrowdMetric(SourceState state, BigDecimal value, String unit, String ordinalLevel, String label,
            DataProvenance provenance) {
    }

    /** Mirrors the required DataProvenance contract fields; null values remain meaningful. */
    public record DataProvenance(String source, String sourceDisplayName, long sourceRegistryVersion,
            SourceState sourceState, Instant observedAt, Instant targetAt, String freshness, Instant fetchedAt,
            Instant staleAt, BigDecimal confidence, String license, String officialUrl, String licenseUrl,
            String attribution, String attributionShort, String metricDefinition, String normalizationVersion,
            List<String> qualityFlags, String forecastIssueId, String comparisonAxis, boolean comparisonEligible,
            String comparisonReasonCode, String comparisonGroupId, UUID collectorRunId, UUID snapshotSetId,
            Integer observedAtSkewSeconds, String scope, String scopeLabel, String mappingType, boolean fallbackUsed,
            UUID provenanceId) {
        public DataProvenance {
            qualityFlags = List.copyOf(qualityFlags);
        }
    }
}
