package io.nullnull.live.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Whether one place may carry a live area's crowd value, and on what terms (BA-090-T2).
 *
 * <p>The card's safety boundary is that an AREA observation is not a POI measurement, and
 * SOURCE_CATALOG §5 says it in the source's own words: "서울 값은 LiveArea 범위의 신호이지 개별
 * Place 입장 인원이나 수용량이 아니다". The consequence this class enforces is narrower and harder:
 * a place with no coverage carries <em>no number at all</em>. Not zero, not the area next to it, not
 * the region's average - the contract's {@code LivePlace.crowd} is nullable precisely so that absence
 * is expressible, and 0 would be a reading nobody took.
 *
 * <p><b>Nothing calls this yet, by owner decision A-033:</b> the Live tab is deferred to last and may
 * end up mocked rather than built. This is the clause's decision, not a shipped feature - it is kept
 * because what it refuses (a zero nobody measured, a neighbouring area's reading) is the part that was
 * expensive to establish, and because BA-090-T2 is proven against it. If Live is mocked, delete it.
 *
 * <p>Two conditions, deliberately separate. A mapping says where a place would read its value from;
 * an observation is whether there is a value to read. A place mapped to an area that has not reported
 * is exactly as uncovered as a place with no mapping - and that is the case a "we have a mapping, so
 * attach something" implementation gets wrong.
 */
public record LiveCoverage(String mappingType, boolean fallbackUsed, BigDecimal confidence,
        UUID liveAreaId) {

    /**
     * No area value may be attached. {@code fallbackUsed} is false because no fallback was used -
     * nothing was. The name is NONE rather than UNKNOWN: we know the answer, and the answer is that
     * this place has no live coverage.
     */
    public static final String NONE = "NONE";

    public LiveCoverage {
        Objects.requireNonNull(mappingType, "mappingType");
        if ((liveAreaId == null) != NONE.equals(mappingType)) {
            // The two halves are one fact. A NONE that names an area, or an area with no name for how
            // it was reached, is the shape this class exists to make unrepresentable.
            throw new IllegalArgumentException("covered and naming an area are the same fact");
        }
    }

    public boolean covered() {
        return liveAreaId != null;
    }

    public static LiveCoverage none() {
        return new LiveCoverage(NONE, false, null, null);
    }

    /**
     * @param mappings           every reviewed mapping, by place
     * @param areasWithObservation the areas that actually have a current reading
     */
    public static LiveCoverage decide(UUID placeId, Map<UUID, LiveAreaMapping> mappings,
            Set<UUID> areasWithObservation) {
        Objects.requireNonNull(placeId, "placeId");
        LiveAreaMapping mapping = mappings.get(placeId);
        if (mapping == null || !areasWithObservation.contains(mapping.liveAreaId())) {
            return none();
        }
        return new LiveCoverage(mapping.mappingType(), mapping.fallbackUsed(), mapping.confidence(),
                mapping.liveAreaId());
    }
}
