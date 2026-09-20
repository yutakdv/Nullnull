package io.nullnull.live.application;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.application.CrowdProvenanceProjection.DataProvenance;
import io.nullnull.live.domain.LiveCoverage;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Attaches an area's reading to a place, on the terms the mapping allows.
 *
 * <p>SOURCE_CATALOG §5 states the rule in the source's own words - "서울 값은 {@code LiveArea} 범위의
 * 신호이지 개별 {@code Place} 입장 인원이나 수용량이 아니다" - and then the obligation: "POI에 붙일 때
 * {@code mappingType}, {@code confidence}, {@code fallbackUsed}를 항상 제공한다". Those three fields
 * are exactly what this class rewrites, and nothing else.
 *
 * <p><strong>Why they have to be rewritten rather than passed through.</strong> The stored row's
 * {@code mapping_type} is DIRECT, and that is correct FOR THE ROW: the reading is directly about the
 * area, which is its subject. {@code JdbcSeoulLiveSnapshotStore} spells out why AREA there would be
 * wrong - it would say the row is a place measurement taken from a neighbour. But the moment the
 * same number appears under a place, the question changes from "what was measured" to "how did this
 * place come to carry it", and the honest answer is the mapping's: AREA, or AREA_FALLBACK when only
 * a surrounding area is mapped. Passing DIRECT through would tell a client this place was measured.
 *
 * <p><strong>What is deliberately NOT rewritten.</strong> {@code scope} stays LIVE_AREA and
 * {@code scopeLabel} stays the area's, because the measurement's subject does not change by being
 * displayed somewhere else - that is the fact the contract's {@code mappingType} sits beside, not a
 * contradiction of it. {@code observedAt}, {@code freshness} and the quality flags stay because they
 * describe the reading. Rewriting scope would erase the one field that says this number is about a
 * region.
 */
@Component
public class LivePlaceProjection {

    /**
     * The area's reading as this place may carry it.
     *
     * @param coverage must be {@link LiveCoverage#covered()}; an uncovered place carries no metric
     *     at all and the caller sends {@code null}, which is what the contract's nullable
     *     {@code LivePlace.crowd} is for. Zero is not an option: it is a reading nobody took.
     */
    public CrowdMetric attachedTo(CrowdMetric areaReading, LiveCoverage coverage) {
        Objects.requireNonNull(areaReading, "areaReading");
        Objects.requireNonNull(coverage, "coverage");
        if (!coverage.covered()) {
            // Not a fallback to "attach it anyway". A caller that reaches here with NONE has decided
            // absence and then asked for a value, and one of the two is a defect.
            throw new IllegalArgumentException("an uncovered place carries no reading");
        }
        DataProvenance source = areaReading.provenance();
        if (source.comparisonEligible()) {
            // Invariant 8, as a guard rather than a comment. An AREA observation displayed under a
            // POI must never be comparison-eligible: comparing two places by the numbers of the
            // districts they sit in is the fabricated comparison that invariant forbids, and it
            // would read as a real one because both sides carry full provenance. Today the stored
            // row cannot be eligible (an area has no numeric value and retains LIVE_AREA scope).
            // This fails loudly if that three-class chain changes rather than
            // shipping a comparison nobody authorised.
            throw new IllegalStateException("an area reading attached to a place is never comparable");
        }
        DataProvenance attached = new DataProvenance(source.source(), source.sourceDisplayName(),
                source.sourceRegistryVersion(), source.sourceState(), source.observedAt(), source.targetAt(),
                source.freshness(), source.fetchedAt(), source.staleAt(),
                // The mapping's confidence, not the measurement's. The stored reading has none - the
                // provider publishes a stage, not an interval - and the uncertainty that actually
                // stands between this number and this place is how sure the review is that the place
                // sits in that area. That is the number §5 asks for beside mappingType.
                coverage.confidence(),
                source.license(), source.officialUrl(), source.licenseUrl(), source.attribution(),
                source.attributionShort(), source.metricDefinition(), source.normalizationVersion(),
                source.qualityFlags(), source.forecastIssueId(), source.comparisonAxis(),
                source.comparisonEligible(), source.comparisonReasonCode(), source.comparisonGroupId(),
                source.collectorRunId(), source.snapshotSetId(), source.observedAtSkewSeconds(),
                source.scope(), source.scopeLabel(),
                coverage.mappingType(), coverage.fallbackUsed(), source.provenanceId());
        return new CrowdMetric(areaReading.state(), areaReading.value(), areaReading.unit(),
                areaReading.ordinalLevel(), areaReading.label(), attached);
    }
}
