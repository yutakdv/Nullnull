package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.CrowdForecastQuery.Snapshot;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.application.LiveAreaCrowdQuery.AreaReading;
import io.nullnull.crowd.application.ReplayManifestReader;
import io.nullnull.crowd.domain.SourceState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** An approved manifest is usable only while its immutable source and ordered entries still agree. */
@Repository
public class JdbcReplayManifestReader implements ReplayManifestReader {

    private static final String FORMAT = "replay-manifest-v1";

    private final JdbcTemplate jdbc;
    private final CrowdSnapshotRows rows = new CrowdSnapshotRows();
    private final CrowdProvenanceProjection provenance;

    public JdbcReplayManifestReader(JdbcTemplate jdbc, CrowdProvenanceProjection provenance) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReplayBatch> read(UUID manifestId, Instant now) {
        Objects.requireNonNull(manifestId, "manifestId");
        Objects.requireNonNull(now, "now");
        List<Manifest> manifests = jdbc.query("""
                SELECT manifest.id, manifest.schema_version, manifest.source_code,
                       manifest.source_registry_version, manifest.checksum,
                       manifest.source_license_snapshot, manifest.captured_from, manifest.captured_to,
                       manifest.approved_at,
                       (revision.canonical_contract->'license')::text AS approved_license,
                       revision.canonical_contract->>'approvalState' AS approval_state,
                       (revision.canonical_contract->>'enabled')::boolean AS source_enabled
                  FROM replay_manifests manifest
                  JOIN source_registry_revisions revision
                    ON revision.source_code = manifest.source_code
                   AND revision.version = manifest.source_registry_version
                 WHERE manifest.id = ?
                """, (result, row) -> new Manifest(
                result.getObject("id", UUID.class), result.getString("schema_version"),
                result.getString("source_code"), result.getLong("source_registry_version"),
                result.getString("checksum"), result.getString("source_license_snapshot"),
                instant(result.getTimestamp("captured_from")), instant(result.getTimestamp("captured_to")),
                instant(result.getTimestamp("approved_at")), result.getString("approved_license"),
                result.getString("approval_state"), result.getBoolean("source_enabled")), manifestId);
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        Manifest manifest = manifests.getFirst();
        if (!FORMAT.equals(manifest.format()) || manifest.approvedAt().isAfter(now)
                || !manifest.enabled()
                || !("DEV_APPROVED".equals(manifest.approvalState())
                    || "PROD_APPROVED".equals(manifest.approvalState()))
                || !manifest.licenseSnapshot().equals(manifest.approvedLicense())) {
            return Optional.empty();
        }

        List<Entry> entries = jdbc.query("""
                SELECT entry.sequence, entry.crowd_snapshot_id, point.live_area_id,
                       point.source_code, point.source_registry_version, point.source_state,
                       point.scope, point.observed_at
                  FROM replay_manifest_entries entry
                  JOIN crowd_snapshots point ON point.id = entry.crowd_snapshot_id
                 WHERE entry.manifest_id = ?
                 ORDER BY entry.sequence
                """, (result, row) -> new Entry(result.getInt("sequence"),
                result.getObject("crowd_snapshot_id", UUID.class),
                result.getObject("live_area_id", UUID.class), result.getString("source_code"),
                result.getLong("source_registry_version"), result.getString("source_state"),
                result.getString("scope"), instant(result.getTimestamp("observed_at"))), manifestId);
        if (!valid(manifest, entries)) {
            return Optional.empty();
        }

        UUID[] ids = entries.stream().map(Entry::snapshotId).toArray(UUID[]::new);
        Map<UUID, Snapshot> byId = new HashMap<>();
        jdbc.query(CrowdSnapshotRows.POINT + " WHERE point.id = ANY (?)", result -> {
            Snapshot point = rows.map(result, 0);
            byId.put(point.id(), point);
        }, (Object) ids);
        if (byId.size() != entries.size()) {
            return Optional.empty();
        }
        List<AreaReading> readings = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Snapshot point = byId.get(entry.snapshotId());
            if (point == null) {
                return Optional.empty();
            }
            // The stored observation is immutable and remains LIVE/STALE. Replay is a separate
            // read namespace, so the public projection sees REPLAY without rewriting that row.
            Snapshot replayed = new Snapshot(point.id(), point.snapshotSetId(), point.collectorRunId(),
                    point.placeId(), point.source(), SourceState.REPLAY, point.observedAt(),
                    point.targetAt(), point.fetchedAt(), point.staleAt(), point.metricCode(),
                    point.value(), point.unit(), point.ordinalLevel(), point.confidence(),
                    point.qualityFlags(), point.forecastIssueId(), point.comparisonGroupId(),
                    point.normalizationVersion(), point.observedAtSkewSeconds(), point.scope(),
                    point.scopeLabel(), point.mappingType(), point.fallbackUsed(), point.incidentActive());
            readings.add(new AreaReading(entry.areaId(), provenance.project(replayed, now, false)));
        }
        Instant replayAt = entries.stream().map(Entry::observedAt).max(Instant::compareTo).orElseThrow();
        return Optional.of(new ReplayBatch(manifest.id(), manifest.approvedAt(), replayAt, readings));
    }

    private static boolean valid(Manifest manifest, List<Entry> entries) {
        if (entries.isEmpty()) {
            return false;
        }
        Set<UUID> areas = new HashSet<>();
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.sequence() != index || entry.areaId() == null || !areas.add(entry.areaId())
                    || !manifest.sourceCode().equals(entry.sourceCode())
                    || manifest.sourceVersion() != entry.sourceVersion()
                    || !"LIVE_AREA".equals(entry.scope())
                    || !("LIVE".equals(entry.state()) || "STALE".equals(entry.state()))
                    || entry.observedAt() == null || entry.observedAt().isBefore(manifest.from())
                    || entry.observedAt().isAfter(manifest.to())) {
                return false;
            }
            canonical.append(index).append(':').append(entry.snapshotId()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).equals(manifest.checksum());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Manifest(UUID id, String format, String sourceCode, long sourceVersion,
            String checksum, String licenseSnapshot, Instant from, Instant to,
            Instant approvedAt, String approvedLicense, String approvalState, boolean enabled) {
    }

    private record Entry(int sequence, UUID snapshotId, UUID areaId, String sourceCode,
            long sourceVersion, String state, String scope, Instant observedAt) {
    }
}
