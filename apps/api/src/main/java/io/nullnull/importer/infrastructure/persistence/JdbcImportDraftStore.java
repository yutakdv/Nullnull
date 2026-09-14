package io.nullnull.importer.infrastructure.persistence;

import io.nullnull.importer.application.ImportDraftStore;
import io.nullnull.importer.domain.ImportDraft;
import io.nullnull.importer.domain.ImportDraftContent;
import io.nullnull.importer.domain.UnresolvedToken;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** V028, read and written. The two jsonb columns are the draft's structure and its open questions. */
@Repository
public class JdbcImportDraftStore implements ImportDraftStore {

    private static final String COLUMNS = """
            id, owner_id, status, version, structured_draft, unresolved_tokens, confirmed_trip_id,
            confirmed_at, expires_at, created_at
            """;

    private static final TypeReference<List<UnresolvedToken>> TOKENS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcImportDraftStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<ImportDraft> find(UUID ownerId, UUID draftId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM itinerary_import_drafts WHERE owner_id = ? AND id = ?")
                .param(ownerId).param(draftId).query(this::draft).optional();
    }

    @Override
    public Optional<ImportDraft> findForUpdate(UUID ownerId, UUID draftId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM itinerary_import_drafts WHERE owner_id = ? AND id = ? FOR UPDATE")
                .param(ownerId).param(draftId).query(this::draft).optional();
    }

    @Override
    public void insert(ImportDraft draft) {
        jdbc.sql("""
                INSERT INTO itinerary_import_drafts
                    (id, owner_id, status, version, structured_draft, unresolved_tokens,
                     confirmed_trip_id, confirmed_at, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """)
                .param(draft.id()).param(draft.ownerId()).param(draft.status().name())
                .param(draft.version())
                .param(json.writeValueAsString(draft.content()))
                .param(json.writeValueAsString(draft.unresolved()))
                .param(draft.confirmedTripId())
                .param(draft.confirmedAt() == null ? null : Timestamp.from(draft.confirmedAt()))
                .param(Timestamp.from(draft.expiresAt())).param(Timestamp.from(draft.createdAt()))
                .update();
    }

    @Override
    public boolean saveRemap(ImportDraft draft, long previousVersion) {
        return jdbc.sql("""
                UPDATE itinerary_import_drafts
                   SET status = ?, version = ?, structured_draft = ?::jsonb, unresolved_tokens = ?::jsonb
                 WHERE id = ? AND owner_id = ? AND version = ?
                """)
                .param(draft.status().name()).param(draft.version())
                .param(json.writeValueAsString(draft.content()))
                .param(json.writeValueAsString(draft.unresolved()))
                .param(draft.id()).param(draft.ownerId()).param(previousVersion)
                .update() == 1;
    }

    @Override
    public void markConfirmed(UUID draftId, UUID tripId, Instant confirmedAt) {
        jdbc.sql("""
                UPDATE itinerary_import_drafts
                   SET status = 'CONFIRMED', confirmed_trip_id = ?, confirmed_at = ?
                 WHERE id = ?
                """)
                .param(tripId).param(Timestamp.from(confirmedAt)).param(draftId).update();
    }

    private ImportDraft draft(ResultSet row, int rowNumber) throws SQLException {
        Timestamp confirmedAt = row.getTimestamp("confirmed_at");
        return new ImportDraft(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                ImportDraft.Status.valueOf(row.getString("status")), row.getLong("version"),
                json.readValue(row.getString("structured_draft"), ImportDraftContent.class),
                json.readValue(row.getString("unresolved_tokens"), TOKENS),
                row.getObject("confirmed_trip_id", UUID.class),
                confirmedAt == null ? null : confirmedAt.toInstant(),
                row.getTimestamp("expires_at").toInstant(), row.getTimestamp("created_at").toInstant());
    }
}
