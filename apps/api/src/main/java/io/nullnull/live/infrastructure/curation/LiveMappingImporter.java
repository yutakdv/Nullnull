package io.nullnull.live.infrastructure.curation;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies human-reviewed place-to-area links; collection never writes this table. */
@Service
public class LiveMappingImporter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LiveMappingImporter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Plan(List<Mapping> mappings) {
        public Plan {
            mappings = List.copyOf(Objects.requireNonNull(mappings, "mappings"));
            if (mappings.isEmpty() || mappings.stream().map(Mapping::placeId).distinct().count() != mappings.size()) {
                throw new IllegalArgumentException("the plan needs distinct places");
            }
        }
    }

    public record Mapping(UUID placeId, String areaName, String mappingType, BigDecimal confidence,
            boolean fallbackUsed, Instant verifiedAt, String evidenceUrl) {
        public Mapping {
            Objects.requireNonNull(placeId, "placeId");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            if (areaName == null || areaName.isBlank() || areaName.length() > 200) {
                throw new IllegalArgumentException("an area name is required");
            }
            if (!("AREA".equals(mappingType) && !fallbackUsed
                    || "AREA_FALLBACK".equals(mappingType) && fallbackUsed)) {
                throw new IllegalArgumentException("mapping type and fallbackUsed disagree");
            }
            if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0
                    || confidence.scale() > 4) {
                throw new IllegalArgumentException("confidence must be within [0, 1] at four decimal places");
            }
            URI evidence;
            try {
                evidence = URI.create(evidenceUrl);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("an HTTPS evidence URL is required", invalid);
            }
            if (!"https".equals(evidence.getScheme()) || evidence.getHost() == null
                    || evidence.getUserInfo() != null) {
                throw new IllegalArgumentException("an HTTPS evidence URL is required");
            }
        }
    }

    @Transactional
    public List<UUID> importPlan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Instant now = clock.instant();
        for (Mapping mapping : plan.mappings()) {
            if (mapping.verifiedAt().isAfter(now)) {
                throw new IllegalArgumentException("a future review cannot be imported");
            }
            // Lock the canonical place so two curations cannot both see an empty mapping set.
            List<UUID> places = jdbc.query("SELECT id FROM places WHERE id = ? FOR UPDATE",
                    (row, ignored) -> row.getObject(1, UUID.class), mapping.placeId());
            if (places.size() != 1) {
                throw new IllegalArgumentException("the plan names an unknown place");
            }
            List<UUID> areas = jdbc.query("""
                    SELECT id FROM live_areas
                     WHERE source_code = 'SEOUL_CITYDATA' AND name = ? AND status = 'ACTIVE'
                    """, (row, ignored) -> row.getObject(1, UUID.class), mapping.areaName());
            if (areas.size() != 1) {
                throw new IllegalArgumentException("the plan needs one active Seoul area with that name");
            }
            UUID areaId = areas.get(0);
            List<UUID> previous = jdbc.query("SELECT live_area_id FROM seoul_live_area_maps WHERE place_id = ?",
                    (row, ignored) -> row.getObject(1, UUID.class), mapping.placeId());
            if (previous.stream().anyMatch(id -> !id.equals(areaId))) {
                throw new IllegalArgumentException("the place already has another reviewed area");
            }
            jdbc.update("""
                    INSERT INTO seoul_live_area_maps
                        (id, place_id, live_area_id, mapping_type, confidence, fallback_used, verified_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (place_id, live_area_id) DO UPDATE
                       SET mapping_type = EXCLUDED.mapping_type,
                           confidence = EXCLUDED.confidence,
                           fallback_used = EXCLUDED.fallback_used,
                           verified_at = EXCLUDED.verified_at
                    """, UUID.randomUUID(), mapping.placeId(), areaId, mapping.mappingType(),
                    mapping.confidence(), mapping.fallbackUsed(), Timestamp.from(mapping.verifiedAt()));
        }
        return plan.mappings().stream().map(Mapping::placeId).toList();
    }
}
