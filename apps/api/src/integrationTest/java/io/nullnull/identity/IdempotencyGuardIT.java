package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-002-T3 on a real PostgreSQL: a guarded command runs at most once per
 * {@code (owner, route template, key)}, a failure rolls back the reservation together with the domain
 * effect, and a key reused for a different request or a different resource is rejected instead of
 * replayed. Retention, the configured TTL and the lock wait bound are covered by
 * {@link IdempotencyConfigurationIT}; the owner-lifecycle lock by {@link OwnerLifecycleLockIT}.
 *
 * <p>The test class is deliberately not transactional: each call must commit or roll back on its own.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 idempotency guard on real PostgreSQL")
class IdempotencyGuardIT {

    private static final String ROUTE = "POST /trips/{tripId}/candidates";
    private static final String OTHER_ROUTE = "DELETE /trips/{tripId}/candidates/{candidateId}";

    /** U+0000 built without a source escape: PostgreSQL jsonb refuses it in any string. */
    private static final String NUL = String.valueOf((char) 0);

    /**
     * The six characters Jackson writes for U+0000, as literal text. Split so this source file is not
     * itself a unicode escape (JLS 3.3). PostgreSQL stores these without complaint.
     */
    private static final String SIX_CHARACTER_TEXT = "\\" + "u0000";

    /** What a command returns; the status token stands for a value that must not be stored. */
    record Receipt(String requestId, String statusToken) {
    }

    /** The shape jsonb expands most: an array of one-character elements. */
    record Bulk(List<Integer> values) {
    }

    /** A projection whose numbers PostgreSQL re-renders far wider than Jackson wrote them. */
    record Wide(List<Double> values) {
    }

    /** What the same command stores for replay. */
    record StoredReceipt(String requestId) {
    }

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    OwnerRepository owners;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Autowired
    ObjectMapper json;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    @DisplayName("BA-002-T3 a completed command replays its stored response instead of running again")
    void completedCommandsReplayAndNeverRunTwice() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{\"placeId\":\"p-1\"}");
        AtomicInteger runs = new AtomicInteger();

        GuardedResponse first = guard.execute(owner.id(), ROUTE, key, hash,
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-1", "token-1"));
                },
                Function.identity());
        GuardedResponse replay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-2", "token-2"));
                },
                Function.identity());
        GuardedResponse secondReplay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-3", "token-3"));
                },
                Function.identity());

        assertThat(runs).hasValue(1);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(first.status()).isEqualTo(201);
        // jsonb normalises whitespace and key order, so the replay is semantically identical to the
        // first response and byte-stable across replays.
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
        assertThat(secondReplay.body()).isEqualTo(replay.body());
        assertThat(replay.body()).contains("r-1");
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
    }

    @Test
    @DisplayName("BA-002-T3 a failure midway rolls back the record and the effect, and the retry re-runs")
    void failureRollsBackTheReservationAndTheEffect() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{\"placeId\":\"p-1\"}");
        UUID effectOwnerId = UuidV7.create(clock);

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hash,
                () -> {
                    owners.create(OwnerFixtures.anonymous(effectOwnerId, clock));
                    // Flush so the effect really reaches the database before the failure.
                    entityManager.flush();
                    assertThat(ownerCount(effectOwnerId)).isOne();
                    throw new IllegalStateException("command failed midway");
                },
                Function.identity()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(ownerCount(effectOwnerId)).isZero();
        assertThat(recordCount(owner.id(), ROUTE, key)).isZero();

        GuardedResponse retry = guard.execute(owner.id(), ROUTE, key, hash,
                () -> {
                    owners.create(OwnerFixtures.anonymous(effectOwnerId, clock));
                    return new CommandOutcome<>(201, new Receipt("r-1", "token-1"));
                },
                Function.identity());

        assertThat(retry.replayed()).isFalse();
        assertThat(ownerCount(effectOwnerId)).isOne();
        assertThat(recordStatus(owner.id(), ROUTE, key)).isEqualTo(201);
    }

    @Test
    @DisplayName("BA-002-T3 the same key with a different body is IDEMPOTENCY_KEY_REUSED")
    void theSameKeyWithAnotherRequestIsRejected() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        guard.execute(owner.id(), ROUTE, key, hashOf("{\"placeId\":\"p-1\"}"),
                () -> new CommandOutcome<>(201, new Receipt("r-1", "token-1")), Function.identity());

        AtomicInteger runs = new AtomicInteger();
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{\"placeId\":\"p-2\"}"),
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-2", "token-2"));
                },
                Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));
        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("BA-002-T3 the same key and body sent to another resource is IDEMPOTENCY_KEY_REUSED")
    void theSameKeyAndBodyOnAnotherResourceIsRejected() {
        // docs/engineering/TEST_STRATEGY.md "자원 간 멱등 key 재사용 검증": same owner, same route
        // template, same body, another trip id. The path parameters live in the request hash, so the
        // second call must be rejected and must not return the first resource's response.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String body = "{\"placeId\":\"p-1\"}";
        GuardedResponse first = guard.execute(owner.id(), ROUTE, key, hashOf("t-1", body),
                () -> new CommandOutcome<>(201, new Receipt("r-1", "token-1")), Function.identity());
        assertThat(first.replayed()).isFalse();

        AtomicInteger runs = new AtomicInteger();
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("t-2", body),
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-2", "token-2"));
                },
                Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED));

        assertThat(runs).hasValue(0);
        // The other resource's response is neither returned nor overwritten.
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
        assertThat(storedBody(owner.id(), ROUTE, key)).contains("r-1").doesNotContain("r-2");
        assertThat(recordHash(owner.id(), ROUTE, key)).isEqualTo(hashOf("t-1", body));
    }

    @Test
    @DisplayName("BA-002-T3 the same key on another route or for another owner is a separate reservation")
    void reservationsAreScopedByOwnerAndRoute() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        Owner otherOwner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{\"placeId\":\"p-1\"}");
        AtomicInteger runs = new AtomicInteger();

        guard.execute(owner.id(), ROUTE, key, hash, () -> outcome(runs), Function.identity());
        guard.execute(owner.id(), OTHER_ROUTE, key, hash, () -> outcome(runs), Function.identity());
        guard.execute(otherOwner.id(), ROUTE, key, hash, () -> outcome(runs), Function.identity());

        assertThat(runs).hasValue(3);
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
        assertThat(recordCount(owner.id(), OTHER_ROUTE, key)).isOne();
        assertThat(recordCount(otherOwner.id(), ROUTE, key)).isOne();
    }

    @Test
    @DisplayName("BA-002-T3 the stored projection holds less than the returned response")
    void theStoredProjectionCanDropFieldsFromTheResponse() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{}");

        GuardedResponse first = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(202, new Receipt("r-1", "token-1")),
                receipt -> new StoredReceipt(receipt.requestId()));

        assertThat(first.body()).contains("token-1");
        assertThat(storedBody(owner.id(), ROUTE, key)).contains("r-1").doesNotContain("token-1");

        GuardedResponse replay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(202, new Receipt("r-9", "token-9")),
                receipt -> new StoredReceipt(receipt.requestId()));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).doesNotContain("token");
    }

    @Test
    @DisplayName("BA-002-T3 a projected replay returns the stored projection verbatim, without rehydration")
    void aProjectedReplayIsNotRehydratedByTheGuard() {
        // Pinned for BA-012: DeletionReceipt requires statusToken, and this replay does not carry it.
        // The guard rehydrates nothing, so the caller that projects must restore the omitted fields
        // itself before returning the replayed body.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{}");

        guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(202, new Receipt("r-1", "token-1")),
                receipt -> new StoredReceipt(receipt.requestId()));
        GuardedResponse replay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(202, new Receipt("r-9", "token-9")),
                receipt -> new StoredReceipt(receipt.requestId()));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(storedBody(owner.id(), ROUTE, key));
        assertThat(json.readTree(replay.body()).has("statusToken")).isFalse();
    }

    @Test
    @DisplayName("BA-002-T3 a malformed Idempotency-Key or route is INVALID_REQUEST, not a 500")
    void malformedKeysAndRoutesAreRejectedBeforeAnythingIsReserved() {
        // The header schema in docs/api/openapi.yaml is minLength 16, maxLength 100.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        AtomicInteger runs = new AtomicInteger();

        rejectsAsInvalidRequest(owner, ROUTE, "short-key-12345", runs);
        rejectsAsInvalidRequest(owner, ROUTE, "k".repeat(101), runs);
        rejectsAsInvalidRequest(owner, ROUTE, null, runs);
        rejectsAsInvalidRequest(owner, "", idempotencyKey(), runs);
        rejectsAsInvalidRequest(owner, "R".repeat(201), idempotencyKey(), runs);

        assertThat(runs).hasValue(0);
        assertThat(recordCountForOwner(owner.id())).isZero();
    }

    @Test
    @DisplayName("BA-002-T3 a projection PostgreSQL cannot store fails with a named error, not a driver error")
    void aProjectionWithANulCharacterIsRejected() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                () -> new CommandOutcome<>(201, new Receipt("r-1" + NUL + "end", "token-1")),
                Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.INTERNAL_ERROR))
                .hasMessageContaining("cannot be stored");

        assertThat(recordCount(owner.id(), ROUTE, key)).isZero();
    }

    @Test
    @DisplayName("BA-002-T3 a projection above the stored bound fails with a named error")
    void anOversizedProjectionIsRejected() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String oversized = "x".repeat(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES + 1);

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                () -> new CommandOutcome<>(201, new Receipt(oversized, "token-1")),
                Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.INTERNAL_ERROR))
                .hasMessageContaining("too large");

        assertThat(recordCount(owner.id(), ROUTE, key)).isZero();
    }

    @Test
    @DisplayName("BA-002-T3 a projection at the application limit is stored even after jsonb expands it")
    void aProjectionAtTheApplicationLimitSurvivesJsonbExpansion() {
        // The two bounds do not measure the same bytes. The application counts the compact text it
        // serialised; the column counts response_body::text, which PostgreSQL re-serialises with a
        // space after every ':' and every ','. An array of one-character elements is the most
        // expansion-prone shape there is, so this is exactly where a column bound equal to the
        // application bound would fire first and turn the named error into a constraint violation.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{}");
        Bulk atLimit = bulkOfCompactBytes(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES);
        assertThat(json.writeValueAsString(atLimit).getBytes(StandardCharsets.UTF_8))
                .hasSize(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES);

        GuardedResponse first = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(201, atLimit), Function.identity());

        assertThat(first.replayed()).isFalse();
        // What the column actually holds is longer than what the application measured, so the column
        // bound has to be the looser of the two for the named error to stay in front of it.
        int storedBytes = storedBody(owner.id(), ROUTE, key).getBytes(StandardCharsets.UTF_8).length;
        assertThat(storedBytes).isGreaterThan(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES)
                .isLessThanOrEqualTo(IdempotencyRecord.RESPONSE_BODY_COLUMN_MAX_BYTES);
        // And it replays: reading the row back builds a record from that longer text.
        GuardedResponse replay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(201, new Bulk(List.of(9))), Function.identity());
        assertThat(replay.replayed()).isTrue();
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));

        // One byte over the application bound, the named error fires and nothing is written.
        String overKey = idempotencyKey();
        Bulk overLimit = bulkOfCompactBytes(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES + 1);
        assertThat(json.writeValueAsString(overLimit).getBytes(StandardCharsets.UTF_8))
                .hasSize(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES + 1);
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, overKey, hash,
                () -> new CommandOutcome<>(201, overLimit), Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.INTERNAL_ERROR))
                .hasMessageContaining("too large");
        assertThat(recordCount(owner.id(), ROUTE, overKey)).isZero();
    }

    @Test
    @DisplayName("BA-002-T3 a projection carrying the U+0000 escape as text is stored and replays intact")
    void aProjectionCarryingTheEscapeAsTextIsStored() {
        // Only a real U+0000 is unstorable. These six characters are ordinary text that PostgreSQL
        // accepts, so refusing them would fail a legal command with an unretryable INTERNAL_ERROR.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{}");
        String literal = "r-1" + SIX_CHARACTER_TEXT + "end";

        GuardedResponse first = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(201, new Receipt(literal, "token-1")), Function.identity());

        assertThat(first.replayed()).isFalse();
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
        assertThat(json.readTree(storedBody(owner.id(), ROUTE, key)).get("requestId").asString())
                .isEqualTo(literal);

        GuardedResponse replay = guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(201, new Receipt("r-9", "token-9")), Function.identity());
        assertThat(replay.replayed()).isTrue();
        assertThat(json.readTree(replay.body()).get("requestId").asString()).isEqualTo(literal);
    }

    @Test
    @DisplayName("BA-002-T3 an expired record replays nothing and is replaced by a fresh reservation")
    void anExpiredRecordDoesNotReplayItsStoredResponse() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{\"placeId\":\"p-1\"}");
        AtomicInteger runs = new AtomicInteger();

        guard.execute(owner.id(), ROUTE, key, hash, () -> outcome(runs), Function.identity());
        UUID firstRecordId = recordId(owner.id(), ROUTE, key);
        expire(owner.id(), ROUTE, key);

        GuardedResponse afterExpiry = guard.execute(owner.id(), ROUTE, key, hash,
                () -> outcome(runs), Function.identity());

        assertThat(runs).hasValue(2);
        assertThat(afterExpiry.replayed()).isFalse();
        assertThat(afterExpiry.body()).contains("r-2");
        // The stale row is gone, replaced by one reservation with a future expiry.
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
        assertThat(recordId(owner.id(), ROUTE, key)).isNotEqualTo(firstRecordId);
        assertThat(expiresAt(owner.id(), ROUTE, key)).isAfter(OffsetDateTime.now());
        assertThat(storedBody(owner.id(), ROUTE, key)).contains("r-2");
    }

    @Test
    @DisplayName("BA-002-T3 an expired record does not block a new request that reuses its key")
    void anExpiredRecordDoesNotRejectADifferentRequest() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        AtomicInteger runs = new AtomicInteger();

        guard.execute(owner.id(), ROUTE, key, hashOf("{\"placeId\":\"p-1\"}"),
                () -> outcome(runs), Function.identity());
        expire(owner.id(), ROUTE, key);

        // Past retention the key is free again, so this is a new request and not a key reuse conflict.
        GuardedResponse reused = guard.execute(owner.id(), ROUTE, key, hashOf("{\"placeId\":\"p-2\"}"),
                () -> outcome(runs), Function.identity());

        assertThat(runs).hasValue(2);
        assertThat(reused.replayed()).isFalse();
        assertThat(recordCount(owner.id(), ROUTE, key)).isOne();
        assertThat(recordHash(owner.id(), ROUTE, key)).isEqualTo(hashOf("{\"placeId\":\"p-2\"}"));
    }

    @Test
    @DisplayName("BA-002-T3 a deleted owner cannot run a guarded command")
    void aDeletedOwnerIsRejectedBeforeAnythingIsReserved() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        jdbc.update("UPDATE owners SET deleted_at = now() WHERE id = ?", owner.id());
        String key = idempotencyKey();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                () -> outcome(runs), Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.UNAUTHORIZED));
        assertThat(runs).hasValue(0);
        assertThat(recordCount(owner.id(), ROUTE, key)).isZero();
    }

    private void rejectsAsInvalidRequest(Owner owner, String routeKey, String key, AtomicInteger runs) {
        assertThatThrownBy(() -> guard.execute(owner.id(), routeKey, key, hashOf("{}"),
                () -> outcome(runs), Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.INVALID_REQUEST));
    }

    /**
     * {@code {"values":[0,0,...]}} whose compact JSON text is exactly {@code compactBytes} bytes: the
     * wrapper costs 12 bytes and every element after the first adds one digit and one comma. An odd
     * total is reached by widening the last element to two digits. The caller asserts the real size,
     * so this arithmetic can never quietly drift.
     */
    /**
     * Jackson writes {@code 1.0E18} in six characters; PostgreSQL stores it as numeric and renders it
     * as nineteen digits. Eight thousand of them stay well inside the application bound and land far
     * outside the column bound, which is the band the structural-expansion derivation does not cover.
     */
    @Test
    @DisplayName("BA-002-T3 a projection PostgreSQL widens past the column bound still fails by name")
    void aProjectionWidenedByNumberRenderingFailsWithTheNamedError() {
        // The application bound counts the compact text; the column bound counts response_body::text.
        // Separator expansion is bounded, but number re-rendering is not, so a projection can pass the
        // pre-check and still violate the constraint. The caller must not learn that as a driver error.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        String hash = hashOf("{}");
        Wide widened = wideNumbers(8_000);
        assertThat(json.writeValueAsString(widened).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(IdempotencyRecord.RESPONSE_BODY_MAX_BYTES);

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hash,
                () -> new CommandOutcome<>(201, widened), Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.INTERNAL_ERROR))
                .hasMessageContaining("too large");
        assertThat(recordCount(owner.id(), ROUTE, key)).isZero();
    }

    private static Wide wideNumbers(int elements) {
        return new Wide(new ArrayList<>(Collections.nCopies(elements, 1.0E18)));
    }

    private static Bulk bulkOfCompactBytes(int compactBytes) {
        int elements = (compactBytes - 12) / 2;
        List<Integer> values = new ArrayList<>(Collections.nCopies(elements, 0));
        if ((compactBytes - 12) % 2 == 1) {
            values.set(elements - 1, 10);
        }
        return new Bulk(values);
    }

    private static CommandOutcome<Receipt> outcome(AtomicInteger runs) {
        int run = runs.incrementAndGet();
        return new CommandOutcome<>(201, new Receipt("r-" + run, "token-" + run));
    }

    private static String idempotencyKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return hashOf("t-1", body);
    }

    private static String hashOf(String tripId, String body) {
        return RequestFingerprint.of(ROUTE, Map.of("tripId", tripId), body).sha256Hex();
    }

    /** Ages a record past its retention without touching the guard or waiting for a sweep. */
    private void expire(UUID ownerId, String routeKey, String key) {
        int updated = jdbc.update("UPDATE idempotency_records"
                + " SET created_at = now() - interval '4 days', expires_at = now() - interval '3 days'"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                ownerId, routeKey, key);
        assertThat(updated).isOne();
    }

    private int ownerCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class, id);
    }

    private int recordCount(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                Integer.class, ownerId, routeKey, key);
    }

    private int recordCountForOwner(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_id = ?",
                Integer.class, ownerId);
    }

    private UUID recordId(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT id FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                UUID.class, ownerId, routeKey, key);
    }

    private Integer recordStatus(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT response_status FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                Integer.class, ownerId, routeKey, key);
    }

    private String recordHash(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT request_hash FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                String.class, ownerId, routeKey, key);
    }

    private OffsetDateTime expiresAt(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT expires_at FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                OffsetDateTime.class, ownerId, routeKey, key);
    }

    private String storedBody(UUID ownerId, String routeKey, String key) {
        return jdbc.queryForObject("SELECT response_body::text FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                String.class, ownerId, routeKey, key);
    }
}
