package io.nullnull.live.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceDetail;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogRelationProjectionService;
import io.nullnull.catalog.application.CatalogRelationProjectionService.CatalogRelatedPlaces;
import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.application.LiveAreaCrowdQuery;
import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.live.domain.LiveAreaMapping;
import io.nullnull.live.domain.LiveCoverage;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code listLiveAreaPlaces} and {@code getLivePlace}: which of our places an area covers, and what
 * one place's Live screen may say.
 *
 * <p>This is the first caller {@link LiveCoverage} has ever had. Until now the rule that an area
 * observation is not a POI measurement was enforced by a class nothing invoked, which is a rule
 * nobody was following - BA-090-T2 proved the decision and this slice puts it on the path.
 *
 * <p><strong>Places come from the catalog's own projection, never from a join here.</strong>
 * {@code embeddedSummaries} and {@code detail} apply the publication gate, resolve a deprecated id
 * to its canonical row and pick the owner's locale. A query in this module that read {@code places}
 * directly would serve KTO-derived text through a route the fail-closed decision does not cover,
 * which is the exact thing that decision exists to prevent.
 *
 * <p><strong>{@code dataState} is derived from the stored reading's own state and nothing else.</strong>
 * The contract types it as {@code SourceState}, whose six values include {@code FORECAST}, and a
 * later reader will be tempted to fill that in from the KTO concentration series. PM-013 is the
 * standing refusal: that series is a relative figure over the thirty days from the day it was
 * queried, so it is verified per DAY, and presenting it here - on a screen whose other readings are
 * current to the minute - would claim a time resolution nobody measured. The Seoul feed writes LIVE
 * or STALE; an uncovered place is UNAVAILABLE. Nothing on this path may invent a third answer.
 */
@Service
public class LivePlaceQueryService {

    private final LiveCapability capability;
    private final LiveAreaStore areas;
    private final LiveAreaMappingStore mappings;
    private final LiveAreaCrowdQuery readings;
    private final LivePlaceProjection projection;
    private final CatalogPlaceProjectionService places;
    private final CatalogRelationProjectionService relations;
    private final Clock clock;

    public LivePlaceQueryService(LiveCapability capability, LiveAreaStore areas, LiveAreaMappingStore mappings,
            LiveAreaCrowdQuery readings, LivePlaceProjection projection, CatalogPlaceProjectionService places,
            CatalogRelationProjectionService relations, Clock clock) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.areas = Objects.requireNonNull(areas, "areas");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.places = Objects.requireNonNull(places, "places");
        this.relations = Objects.requireNonNull(relations, "relations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One place per reviewed mapping into this area, each with the terms it carries the reading on. */
    public record LivePlaceRow(CatalogPlaceSummary place, String mappingType, boolean fallbackUsed,
            CrowdMetric crowd) {
    }

    /** One place's Live screen. {@code crowd} is null exactly when {@code dataState} is UNAVAILABLE. */
    public record LivePlaceDetailRow(CatalogPlaceDetail place, SourceState dataState, CrowdMetric crowd,
            CatalogRelatedPlaces related) {
    }

    @Transactional(readOnly = true)
    public List<LivePlaceRow> placesInArea(OwnerContext owner, UUID areaId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(areaId, "areaId");
        capability.require();
        requireKnownArea(areaId);

        Map<UUID, LiveAreaMapping> byPlace = mappings.forArea(areaId);
        if (byPlace.isEmpty()) {
            // An area nobody has mapped a place into is an empty list, not a 404. The area exists and
            // reports; what is absent is our own review work, and saying "no such area" would blame
            // the provider for a gap on our side.
            return List.of();
        }
        // ONE read of the area's reading, and the observation set derived from it. Asking twice -
        // once for "is there a reading" and once for "give me the reading" - is the same question
        // twice, and the second answer could differ from the first if a collector committed in
        // between, which would make a place's coverage and its number disagree inside one response.
        Map<UUID, CrowdMetric> readings = readingsByArea(List.of(areaId));
        Set<UUID> observed = Set.copyOf(readings.keySet());
        CrowdMetric reading = readings.get(areaId);

        List<UUID> requested = List.copyOf(byPlace.keySet());
        Map<UUID, UUID> canonical = places.readableCanonicalIds(requested);
        Map<UUID, CatalogPlaceSummary> summaries = new LinkedHashMap<>();
        places.embeddedSummaries(owner, requested).forEach(summary -> summaries.put(summary.id(), summary));

        Map<UUID, LivePlaceRow> byCanonical = new LinkedHashMap<>();
        for (UUID requestedId : requested) {
            CatalogPlaceSummary summary = summaries.get(canonical.get(requestedId));
            if (summary == null) {
                // The catalog will not project it - retired with no canonical row, or filtered by the
                // same rules getPlace applies. A LivePlace without its place is not a shape the
                // contract has, and rendering the mapping alone would show a reading under a name we
                // are not allowed to print.
                continue;
            }
            LiveCoverage coverage = LiveCoverage.decide(requestedId, byPlace, observed);
            LivePlaceRow row = new LivePlaceRow(summary, coverage.mappingType(), coverage.fallbackUsed(),
                    coverage.covered() ? projection.attachedTo(reading, coverage) : null);
            byCanonical.merge(summary.id(), row, LivePlaceQueryService::stronger);
        }
        return List.copyOf(byCanonical.values());
    }

    @Transactional(readOnly = true)
    public LivePlaceDetailRow place(OwnerContext owner, UUID requestedPlaceId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requestedPlaceId, "requestedPlaceId");
        capability.require();
        // The catalog answers first: it applies the publication gate, 404s an id it cannot project,
        // and resolves a retired alias. Deciding coverage before that would answer about a place the
        // caller is not allowed to see.
        CatalogPlaceDetail detail = places.detail(owner, requestedPlaceId);

        // Both ids, because the mapping was reviewed against whichever row was canonical that day
        // and a later merge moves the name without moving the review.
        Map<UUID, LiveAreaMapping> byPlace = mappings.forPlaces(List.of(requestedPlaceId, detail.id()));
        UUID coverageKey = byPlace.containsKey(detail.id()) ? detail.id() : requestedPlaceId;
        LiveAreaMapping mapping = byPlace.get(coverageKey);
        List<UUID> mapped = mapping == null ? List.of() : List.of(mapping.liveAreaId());
        // One read here too, for the reason above. An unmapped place asks for nothing at all rather
        // than running a query with an empty list.
        Map<UUID, CrowdMetric> readings = mapped.isEmpty() ? Map.of() : readingsByArea(mapped);
        LiveCoverage coverage = LiveCoverage.decide(coverageKey, byPlace, Set.copyOf(readings.keySet()));

        CrowdMetric crowd = coverage.covered()
                ? projection.attachedTo(readings.get(coverage.liveAreaId()), coverage)
                : null;
        // UNAVAILABLE is the state of a place with no reading to show, and it is not an error: the
        // feature is on, the place exists, and nothing covers it. The two are told apart by the
        // capability gate above, which refuses outright.
        SourceState dataState = crowd == null ? SourceState.UNAVAILABLE : crowd.state();
        return new LivePlaceDetailRow(detail, dataState, crowd,
                relations.relatedPlaces(owner, requestedPlaceId));
    }

    /**
     * An id that is not an ACTIVE area of this source is a 404 rather than an empty list.
     *
     * <p>The distinction the empty list would destroy is "we have no mapped places here" versus "no
     * such area" - and the second is reachable from the first screen, because a retired area keeps
     * its id in anything a client cached. The areas are a public list this session already sees
     * through {@code queryLiveAreas}, so naming an unknown one reveals nothing.
     *
     * <p>Read from the source's active list rather than by id, because that is the one statement the
     * store already serves and the list is a hundred-odd rows. A by-id port method would be a second
     * query for a question the first already answers.
     */
    private void requireKnownArea(UUID areaId) {
        boolean known = areas.activeAreas(SeoulLiveAreaObservation.SOURCE_CODE).stream()
                .anyMatch(area -> area.id().equals(areaId));
        if (!known) {
            throw new ApiException(ProblemCode.NOT_FOUND, "The requested live area is unavailable.");
        }
    }

    /**
     * The newest stored reading for each of these areas. An area with none is ABSENT from the
     * result rather than present with an empty metric, which is what makes the key set the honest
     * answer to "which of these has a value to read" - a mapping says where a place would read its
     * value from, and {@link LiveCoverage} refuses to attach anything without both halves.
     */
    private Map<UUID, CrowdMetric> readingsByArea(List<UUID> areaIds) {
        Map<UUID, CrowdMetric> byArea = new LinkedHashMap<>();
        readings.latestFor(SeoulLiveAreaObservation.SOURCE_CODE, areaIds, clock.instant())
                .forEach(reading -> byArea.put(reading.liveAreaId(), reading.crowd()));
        return byArea;
    }

    /**
     * Two mapped ids that a merge has since pointed at one canonical place: the stronger mapping
     * describes it.
     *
     * <p><strong>Coverage cannot be the tie-break here, and an earlier version of this method used
     * it.</strong> Every mapping in this list names the SAME area - {@code forArea} selected them by
     * it - so {@code LiveCoverage.decide} asks one question for all of them and either all are
     * covered or none are. A "the covered row wins" arm therefore could not fire for any input,
     * which the merge-path test found: it was a guard nobody could reach. What differs between two
     * rows is HOW each place is tied to the area, and that is what has to settle it.
     *
     * <p>AREA beats AREA_FALLBACK, and not as a preference: a direct mapping says this place sits
     * inside the area's published boundary, a fallback says only that a surrounding area is mapped.
     * Once a merge has made them one place, the direct row is the true statement about it and the
     * fallback is a weaker claim about the same fact. Picking by {@code place_id} order - which is
     * what "keep the first" would have done, since the query orders by it - would let a UUID decide
     * which of two evidence grades the reader sees.
     *
     * <p>A tie keeps the row already held, and the caller iterates in {@code place_id} order, so a
     * tie is settled by the lower id. That arm reads off the rows too, so every arrival order gives
     * one answer - the same property {@code CatalogRelationProjectionService.converge} needs and for
     * the same reason.
     *
     * <p>Reachable only after a merge: two rows in {@code seoul_live_area_maps} naming different
     * places, one of which is later deprecated onto the other. Nothing writes that today; it is not
     * left to chance because the alternative is one place appearing twice on the page, which the
     * internal contract already forbids for related places in the same words.
     */
    private static LivePlaceRow stronger(LivePlaceRow kept, LivePlaceRow other) {
        return kept.fallbackUsed() && !other.fallbackUsed() ? other : kept;
    }
}
