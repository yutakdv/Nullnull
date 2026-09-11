package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.IdempotencyRecordStore;
import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcClient rather than JPA, because here the SQL semantics are the behaviour: the conditional insert
 * and the locking read are what make two concurrent callers with the same key serialise, and neither
 * has a faithful JPA expression. There is deliberately no entity for this table.
 */
@Repository
public class JdbcIdempotencyRecordStore implements IdempotencyRecordStore {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO idempotency_records
                (id, owner_id, route_key, idempotency_key, request_hash, created_at, expires_at)
            VALUES (:id, :ownerId, :routeKey, :idempotencyKey, :requestHash, :createdAt, :expiresAt)
            ON CONFLICT (owner_id, route_key, idempotency_key) DO NOTHING
            """;

    private static final String LOCK_EXISTING = """
            SELECT id, owner_id, route_key, idempotency_key, request_hash, response_status,
                   response_body, created_at, expires_at
              FROM idempotency_records
             WHERE owner_id = :ownerId
               AND route_key = :routeKey
               AND idempotency_key = :idempotencyKey
               FOR UPDATE
            """;

    /** SQLSTATE 23514, check_violation. */
    private static final String CHECK_VIOLATION = "23514";

    private static final String RESPONSE_BODY_SIZE_CONSTRAINT = "idempotency_records_response_body_size_check";

    private static final String COMPLETE = """
            UPDATE idempotency_records
               SET response_status = :responseStatus,
                   response_body = CAST(:responseBody AS jsonb)
             WHERE id = :id
            """;

    private static final String DELETE = """
            DELETE FROM idempotency_records
             WHERE id = :id
            """;

    private static final String DELETE_EXPIRED = """
            DELETE FROM idempotency_records
             WHERE expires_at <= :now
            """;

    private final JdbcClient jdbc;

    JdbcIdempotencyRecordStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(IdempotencyRecord reservation) {
        // The insert waits too: a concurrent caller that inserted the same key and has not committed
        // yet holds the unique index entry.
        return BoundedLockWait.on(() -> jdbc.sql(INSERT_IF_ABSENT)
                .param("id", reservation.id())
                .param("ownerId", reservation.ownerId())
                .param("routeKey", reservation.routeKey())
                .param("idempotencyKey", reservation.idempotencyKey())
                .param("requestHash", reservation.requestHash())
                .param("createdAt", utc(reservation.createdAt()))
                .param("expiresAt", utc(reservation.expiresAt()))
                .update()) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<IdempotencyRecord> lockExisting(UUID ownerId, String routeKey, String idempotencyKey) {
        return BoundedLockWait.on(() -> jdbc.sql(LOCK_EXISTING)
                .param("ownerId", ownerId)
                .param("routeKey", routeKey)
                .param("idempotencyKey", idempotencyKey)
                .query(JdbcIdempotencyRecordStore::map)
                .optional());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID recordId, int responseStatus, String responseBodyJson) {
        int updated;
        try {
            updated = jdbc.sql(COMPLETE)
                    .param("id", recordId)
                    .param("responseStatus", responseStatus)
                    .param("responseBody", responseBodyJson)
                    .update();
        } catch (DataIntegrityViolationException violation) {
            if (isResponseBodyTooLarge(violation)) {
                throw new ApiException(ProblemCode.INTERNAL_ERROR,
                        "The command response is too large to store for replay.");
            }
            throw violation;
        }
        if (updated != 1) {
            throw new IllegalStateException("idempotency record disappeared before its response was stored");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID recordId) {
        // Only ever called for a row this transaction already holds FOR UPDATE, so no wait is possible.
        jdbc.sql(DELETE).param("id", recordId).update();
    }

    /**
     * The sweep uses the (expires_at) index and can wait on a row a guarded command is holding, so it
     * runs under the caller's bounded wait like every other locking statement in this store.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteExpired(Instant now) {
        return BoundedLockWait.on(() -> jdbc.sql(DELETE_EXPIRED).param("now", utc(now)).update());
    }

    /**
     * The guard's own size check measures the compact text it is about to send; PostgreSQL stores the
     * value as jsonb and re-renders it, which is wider for separators and, unboundedly, for numbers
     * ({@code 1.0E18} becomes nineteen digits). The column constraint is therefore the authoritative
     * bound, and a caller must not learn about it as a raw driver failure: a violation of that one
     * constraint becomes the same named error the pre-check raises. Any other integrity violation is
     * rethrown untouched rather than absorbed.
     */
    private static boolean isResponseBodyTooLarge(DataIntegrityViolationException violation) {
        Throwable cause = violation.getMostSpecificCause();
        return cause instanceof SQLException sql
                && CHECK_VIOLATION.equals(sql.getSQLState())
                && String.valueOf(sql.getMessage()).contains(RESPONSE_BODY_SIZE_CONSTRAINT);
    }

    private static IdempotencyRecord map(ResultSet rs, int rowNumber) throws SQLException {
        return new IdempotencyRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("route_key"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getObject("response_status", Integer.class),
                rs.getString("response_body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    /** The PostgreSQL driver binds OffsetDateTime to timestamptz; Instant has no inferable SQL type. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
