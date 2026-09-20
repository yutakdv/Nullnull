package io.nullnull.live.application;

import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.domain.LiveResultMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns stored live areas into the {@code LiveAreaResult} the contract describes.
 *
 * <p><strong>The centroid is null, and that is a fact rather than a gap.</strong> Seoul publishes the
 * area names and their readings; it publishes no coordinate for an area anywhere - not in the response
 * (measured: zero coordinate fields at area level), not on the dataset page, and not in the manual
 * (v8.5: 위도/경도/좌표/WGS/경계/polygon all absent; its chapter 2 table lists names only). The contract
 * types the field as {@code GeoPoint | null} for that reason, so this projection passes the absence
 * through instead of inventing a point. A P0 Live screen is a list with the map off, which is why the
 * absence costs nothing today.
 */
@Component
public class LiveAreaProjection {

    /** One stored area and its most recent reading, as the query layer hands it over. */
    public record AreaRow(UUID id, String name, BigDecimal centroidLatitude, BigDecimal centroidLongitude,
            CrowdMetric crowd) {
        public AreaRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(crowd, "crowd");
            // Half a point is not a point. A row with one coordinate is a storage bug, and letting it
            // through would put a GeoPoint with a null member into a response the schema forbids.
            if (centroidLatitude == null ^ centroidLongitude == null) {
                throw new IllegalArgumentException("a centroid needs both coordinates or neither");
            }
        }

        boolean hasCentroid() {
            return centroidLatitude != null;
        }
    }

    public record GeoPointResponse(BigDecimal latitude, BigDecimal longitude) {}

    public record LiveAreaResponse(UUID id, String name, GeoPointResponse centroid, Object boundaryGeoJson,
            CrowdMetric crowd) {}

    public record LiveAreaResultResponse(String mode, List<LiveAreaResponse> areas, Instant generatedAt) {}

    public LiveAreaResultResponse project(List<AreaRow> rows, Instant generatedAt) {
        Objects.requireNonNull(generatedAt, "generatedAt");
        List<AreaRow> areas = rows == null ? List.of() : rows;
        List<SourceState> states = new ArrayList<>(areas.size());
        List<LiveAreaResponse> projected = new ArrayList<>(areas.size());
        for (AreaRow row : areas) {
            states.add(row.crowd().state());
            projected.add(new LiveAreaResponse(row.id(), row.name(),
                    row.hasCentroid()
                            ? new GeoPointResponse(row.centroidLatitude(), row.centroidLongitude())
                            : null,
                    // Never guessed from the centroid and never the other way round: a boundary we do
                    // not have is null, the same as a centroid we do not have.
                    null,
                    row.crowd()));
        }
        // The page's own state is decided in one place, and it is not this one - see LiveResultMode
        // for why the weakest reading on the page is the one the page is allowed to claim.
        return new LiveAreaResultResponse(LiveResultMode.of(states).name(), List.copyOf(projected), generatedAt);
    }
}
