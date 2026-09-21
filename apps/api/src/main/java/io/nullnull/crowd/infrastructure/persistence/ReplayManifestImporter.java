package io.nullnull.crowd.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captures only explicitly approved normalized Seoul observations, never provider response bodies. */
@Service
public class ReplayManifestImporter {

    private static final String SOURCE = "SEOUL_CITYDATA";

    public record Plan(String name, Instant capturedFrom, Instant capturedTo, List<UUID> snapshotIds) {
        public Plan {
            snapshotIds = List.copyOf(Objects.requireNonNull(snapshotIds, "snapshotIds"));
            if (name == null || name.isBlank() || name.length() > 200
                    || capturedFrom == null || capturedTo == null || capturedFrom.isAfter(capturedTo)
                    || snapshotIds.isEmpty() || snapshotIds.size() > 100
                    || snapshotIds.stream().distinct().count() != snapshotIds.size()) {
                throw new IllegalArgumentException("replay plan needs a name, window and distinct snapshots");
            }
        }
    }

    private record Source(long version, String license, boolean enabled, String approval,
            boolean currentEnabled) { }
    private record Point(UUID id, UUID areaId, String areaStatus, long version, String state,
            String scope, String metricCode, String normalizationVersion, Instant observedAt) { }
    private record Existing(UUID id, String format, String source, long version, String checksum,
            String license, Instant from, Instant to) { }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReplayManifestImporter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public UUID importPlan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Instant now = clock.instant();
        if (plan.capturedTo().isAfter(now)) {
            throw new IllegalArgumentException("replay capture window cannot end in the future");
        }
        List<Source> sources = jdbc.query("""
                SELECT registry.current_revision, (revision.canonical_contract->'license')::text,
                       (revision.canonical_contract->>'enabled')::boolean,
                       revision.canonical_contract->>'approvalState', registry.enabled
                  FROM source_registry registry
                  JOIN source_registry_revisions revision
                    ON revision.source_code = registry.code
                   AND revision.version = registry.current_revision
                 WHERE registry.code = ?
                """, (row, ignored) -> new Source(row.getLong(1), row.getString(2),
                row.getBoolean(3), row.getString(4), row.getBoolean(5)), SOURCE);
        if (sources.size() != 1 || !sources.getFirst().enabled() || !sources.getFirst().currentEnabled()
                || !("DEV_APPROVED".equals(sources.getFirst().approval())
                    || "PROD_APPROVED".equals(sources.getFirst().approval()))) {
            throw new IllegalArgumentException("replay source is not approved");
        }
        Source source = sources.getFirst();
        List<Point> points = jdbc.query("""
                SELECT point.id, point.live_area_id, area.status, point.source_registry_version,
                       point.source_state, point.scope, point.metric_code,
                       point.normalization_version, point.observed_at
                  FROM crowd_snapshots point
                  JOIN live_areas area ON area.id = point.live_area_id
                 WHERE point.source_code = ? AND point.id = ANY (?)
                 FOR SHARE
                """, (row, ignored) -> new Point(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getString(3), row.getLong(4), row.getString(5),
                row.getString(6), row.getString(7), row.getString(8),
                row.getTimestamp(9) == null ? null : row.getTimestamp(9).toInstant()),
                SOURCE, plan.snapshotIds().toArray(UUID[]::new));
        Map<UUID, Point> byId = new HashMap<>();
        points.forEach(point -> byId.put(point.id(), point));
        Set<UUID> areas = new HashSet<>();
        for (UUID id : plan.snapshotIds()) {
            Point point = byId.get(id);
            if (point == null || point.areaId() == null || !"ACTIVE".equals(point.areaStatus())
                    || !areas.add(point.areaId())
                    || point.version() != source.version()
                    || !("LIVE".equals(point.state()) || "STALE".equals(point.state()))
                    || !"LIVE_AREA".equals(point.scope())
                    || !SeoulLiveSnapshotStore.METRIC_CODE.equals(point.metricCode())
                    || !SeoulLiveSnapshotStore.NORMALIZATION_VERSION.equals(point.normalizationVersion())
                    || point.observedAt() == null
                    || point.observedAt().isBefore(plan.capturedFrom())
                    || point.observedAt().isAfter(plan.capturedTo())) {
                throw new IllegalArgumentException("replay plan names an ineligible snapshot");
            }
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < plan.snapshotIds().size(); index++) {
            canonical.append(index).append(':').append(plan.snapshotIds().get(index)).append('\n');
        }
        String checksum;
        try {
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
        List<Existing> existing = jdbc.query("""
                SELECT id, schema_version, source_code, source_registry_version, checksum,
                       source_license_snapshot, captured_from, captured_to
                  FROM replay_manifests WHERE name = ? FOR SHARE
                """, (row, ignored) -> new Existing(row.getObject(1, UUID.class), row.getString(2),
                row.getString(3), row.getLong(4), row.getString(5), row.getString(6),
                row.getTimestamp(7).toInstant(), row.getTimestamp(8).toInstant()), plan.name());
        if (!existing.isEmpty()) {
            Existing held = existing.getFirst();
            List<UUID> heldIds = jdbc.query("""
                    SELECT crowd_snapshot_id FROM replay_manifest_entries
                     WHERE manifest_id = ? ORDER BY sequence
                    """, (row, ignored) -> row.getObject(1, UUID.class), held.id());
            if ("replay-manifest-v1".equals(held.format()) && SOURCE.equals(held.source())
                    && held.version() == source.version() && checksum.equals(held.checksum())
                    && source.license().equals(held.license())
                    && plan.capturedFrom().equals(held.from()) && plan.capturedTo().equals(held.to())
                    && plan.snapshotIds().equals(heldIds)) {
                return held.id();
            }
            throw new IllegalArgumentException("replay manifest name belongs to a different approved plan");
        }
        UUID manifestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO replay_manifests
                    (id, name, schema_version, source_code, source_registry_version, checksum,
                     source_license_snapshot, captured_from, captured_to, approved_at, created_at)
                VALUES (?, ?, 'replay-manifest-v1', ?, ?, ?, ?, ?, ?, ?, ?)
                """, manifestId, plan.name(), SOURCE, source.version(), checksum, source.license(),
                Timestamp.from(plan.capturedFrom()), Timestamp.from(plan.capturedTo()),
                Timestamp.from(now), Timestamp.from(now));
        for (int index = 0; index < plan.snapshotIds().size(); index++) {
            jdbc.update("INSERT INTO replay_manifest_entries (manifest_id, crowd_snapshot_id, sequence)"
                    + " VALUES (?, ?, ?)", manifestId, plan.snapshotIds().get(index), index);
        }
        return manifestId;
    }
}
