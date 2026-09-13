package io.nullnull.catalog.application;

import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogRelationQuery.CatalogRelationCandidate;
import io.nullnull.catalog.application.CatalogRelationQuery.CatalogRelationSource;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * BA-024's {@code listRelatedPlaces}: which verified relations this place has, and honestly what we
 * cannot say.
 *
 * <p>Two states, and only two, by owner decision (HANDOFF §9, "재논의 불필요"): candidates our own
 * rules verified are SIMILAR, and everything else is {@code UNKNOWN(SOURCE_DISABLED)}. NONE is not
 * produced. The asymmetry is not arbitrary - SIMILAR is a positive claim that stands on our own
 * evidence, while NONE is a negative one that needs "we looked everywhere", and the official
 * relation source (KTO_RELATED_PLACES) is unapproved, so we cannot have looked everywhere. It stays
 * in the contract's enum because removing a value a client may already render is breaking; it is
 * simply never emitted, which BA-024-T7 pins.
 *
 * <p>EXACT is likewise storable and unreachable: V027 lets it exist only for a confirmed
 * provider-direct relation, and the only such provider is the unapproved one. It is mapped here
 * rather than ignored so the day that source is approved this reads the stored evidence instead of
 * needing a code change.
 */
@Service
public class CatalogRelationProjectionService {

    /**
     * §9's reason, verbatim. It names the situation - the official relation source is switched off -
     * rather than the symptom, so a reader is not left thinking the lookup merely failed today.
     */
    public static final String SOURCE_DISABLED = "SOURCE_DISABLED";

    private final CatalogPlaceProjectionService places;
    private final CatalogRelationQuery relations;
    private final Clock clock;

    public CatalogRelationProjectionService(CatalogPlaceProjectionService places,
            CatalogRelationQuery relations, Clock clock) {
        this.places = places;
        this.relations = relations;
        this.clock = clock;
    }

    /**
     * The place id may be a retired alias, so it is resolved before anything reads relations: the
     * embedded-summary projection resolves a deprecated id to its canonical row exactly as
     * {@code getPlace} does, and a place that projects nothing at all is a 404 rather than an empty
     * relation list. That call is also what applies the publication gate - while it is closed this
     * answers 503 SOURCE_UNAVAILABLE like every sibling route, instead of inventing a 200 whose
     * state would claim a lookup that could not run.
     */
    public CatalogRelatedPlaces relatedPlaces(OwnerContext owner, UUID requestedPlaceId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requestedPlaceId, "requestedPlaceId");
        List<CatalogPlaceSummary> source = places.embeddedSummaries(owner, List.of(requestedPlaceId));
        if (source.isEmpty()) {
            throw new ApiException(ProblemCode.NOT_FOUND, "The requested place is unavailable.");
        }
        UUID canonicalId = source.getFirst().id();
        Instant now = clock.instant();

        List<CatalogRelationCandidate> candidates = relations.candidatesFor(canonicalId, now);
        List<CatalogRelatedPlace> items = hydrate(owner, candidates);
        if (items.isEmpty()) {
            return new CatalogRelatedPlaces(canonicalId, RelationState.UNKNOWN, SOURCE_DISABLED, List.of());
        }
        boolean exact = items.stream().anyMatch(item -> "EXACT".equals(item.relation()));
        return new CatalogRelatedPlaces(canonicalId, exact ? RelationState.EXACT : RelationState.SIMILAR,
                null, items);
    }

    /**
     * A candidate whose target does not project is dropped rather than rendered without its place:
     * {@code RelatedPlace.place} is required, and a target can fail to project for reasons that have
     * nothing to do with the relation (no coordinates, no localization in this locale). Dropping all
     * of them lands on UNKNOWN, which is the same answer as having no candidates - in both cases we
     * have nothing verified to offer.
     */
    private List<CatalogRelatedPlace> hydrate(OwnerContext owner, List<CatalogRelationCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> targets = candidates.stream().map(CatalogRelationCandidate::targetPlaceId).toList();
        Map<UUID, CatalogPlaceSummary> byId = new LinkedHashMap<>();
        places.embeddedSummaries(owner, targets).forEach(summary -> byId.put(summary.id(), summary));

        List<CatalogRelatedPlace> items = new ArrayList<>(candidates.size());
        for (CatalogRelationCandidate candidate : candidates) {
            CatalogPlaceSummary place = byId.get(candidate.targetPlaceId());
            if (place != null) {
                items.add(new CatalogRelatedPlace(place, candidate.relationType(), candidate.relationReason(),
                        provenance(candidate)));
            }
        }
        return List.copyOf(items);
    }

    /**
     * Everything here is read off the stored relation or the revision it pinned. The nulls are the
     * point, and each one is a rule rather than a gap:
     *
     * <ul>
     *   <li>{@code observedAt} is null because a rule-derived relation was never observed, and the
     *       backend rule is explicit that an unknown observedAt stays null instead of borrowing
     *       fetchedAt.
     *   <li>{@code confidence} is null because {@code mapping_certainty} is CONFIRMED or UNCERTAIN,
     *       not a number between 0 and 1. Projecting 1.0 for CONFIRMED would be inventing a measure.
     *   <li>{@code targetAt}, {@code forecastIssueId}, {@code collectorRunId}, {@code snapshotSetId}
     *       and {@code observedAtSkewSeconds} belong to fetched or forecast snapshots. A relation has
     *       no collector run behind it.
     *   <li>{@code comparisonEligible} is false with {@code QUALITATIVE_ONLY}: a relation says two
     *       places are related, never that one is quieter or closer, and invariant 8 forbids letting
     *       a relation stand in for a numeric comparison.
     * </ul>
     *
     * <p>{@code fetchedAt} is when the evidence was recorded, not when this request ran - a property
     * of the row, so two requests describe the same evidence the same way. {@code freshness} is FRESH
     * for everything here because selection already excluded anything past its window; AGING has no
     * threshold policy behind it and would be a number nobody set.
     */
    private static CatalogRelationProvenance provenance(CatalogRelationCandidate candidate) {
        CatalogRelationSource source = candidate.source();
        List<String> qualityFlags = "UNCERTAIN".equals(candidate.mappingCertainty())
                ? List.of("MAPPING_UNCERTAIN")
                : List.of();
        return new CatalogRelationProvenance(source.code(), source.displayName(), source.registryVersion(),
                source.sourceState(), null, null, "FRESH", candidate.recordedAt(), candidate.expiresAt(),
                null, source.license(), source.officialUrl(), source.licenseUrl(), source.attribution(),
                source.metricDefinition(), source.normalizationVersion(), qualityFlags, null, null,
                false, "QUALITATIVE_ONLY", null, null, null, null, source.scope(), null,
                candidate.derivation(), false, candidate.id());
    }

    /** The contract's five, so the two nobody emits are visible here rather than merely absent. */
    public enum RelationState { EXACT, SIMILAR, NONE, CHECKING, UNKNOWN }

    public record CatalogRelatedPlaces(UUID sourcePlaceId, RelationState state, String reason,
            List<CatalogRelatedPlace> items) {}

    public record CatalogRelatedPlace(CatalogPlaceSummary place, String relation, String relationReason,
            CatalogRelationProvenance provenance) {}

    /** The contract's DataProvenance, in the order the schema declares it. */
    public record CatalogRelationProvenance(String source, String sourceDisplayName, long sourceRegistryVersion,
            String sourceState, Instant observedAt, Instant targetAt, String freshness, Instant fetchedAt,
            Instant staleAt, Double confidence, String license, String officialUrl, String licenseUrl,
            String attribution, String metricDefinition, String normalizationVersion, List<String> qualityFlags,
            String forecastIssueId, String comparisonAxis, boolean comparisonEligible, String comparisonReasonCode,
            String comparisonGroupId, UUID collectorRunId, UUID snapshotSetId, Integer observedAtSkewSeconds,
            String scope, String scopeLabel, String mappingType, boolean fallbackUsed, UUID provenanceId) {}
}
