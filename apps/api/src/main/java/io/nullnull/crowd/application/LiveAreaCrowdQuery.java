package io.nullnull.crowd.application;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The newest stored reading for each of a set of live areas.
 *
 * <p>A separate port from {@link CrowdForecastQuery} because the questions differ in their subject:
 * that one is asked about a place and answers with a forecast series, this one is asked about areas
 * and answers with one current reading each. They share the row mapping - including the guard that
 * refuses an unreviewed stage - and share nothing else.
 *
 * <p><strong>An area with no stored reading is not in the answer.</strong> It is not returned with
 * an UNAVAILABLE metric, because a {@code CrowdMetric} carries a {@code DataProvenance} and building
 * one for a reading nobody took means filling thirty fields about a measurement that does not exist.
 * The caller decides what an absent area means; here, absence is absence.
 */
public interface LiveAreaCrowdQuery {

    /** One area's newest reading, already projected with its provenance and freshness. */
    record AreaReading(UUID liveAreaId, CrowdMetric crowd) {
    }

    /**
     * @param now the instant freshness is judged against - a reading past its {@code staleAt} comes
     *     back STALE, never LIVE, and that decision belongs to {@link CrowdProvenanceProjection}
     */
    List<AreaReading> latestFor(String sourceCode, List<UUID> liveAreaIds, Instant now);
}
