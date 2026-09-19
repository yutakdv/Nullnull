package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.DeletionReceiptExpiry;
import io.nullnull.identity.application.ExpiredReceipt;
import io.nullnull.identity.application.SessionService;
import io.nullnull.identity.infrastructure.persistence.DeletionTtlEraser;
import io.nullnull.operations.application.OpsAlarm;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-072: a deletion receipt that expires while its deletion is unfinished is reported once, without an
 * id. The expiry is called directly with the worker off, so no background sweep in this context reaches
 * these rows first.
 *
 * <p>The clock starts in 2099, past every other integration class's clock, so a sweep those classes run
 * never reaches these rows. The reverse is not true: an expiry here clears every token due by then in
 * the shared database - the same kind of side effect DeletionIT's sweeps have. That is why each call's
 * lines are compared with that call's own answer rather than counted.
 */
@SpringBootTest(properties = "nullnull.jobs.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        DeletionReceiptExpiryIT.Time.class})
@Tag("identity-safety")
@DisplayName("BA-072 deletion receipt expiry")
class DeletionReceiptExpiryIT {

    @TestConfiguration static class Time {
        @Bean @Primary MutableClock receiptClock() {
            return MutableClock.at(Instant.parse("2099-01-01T00:00:00Z"));
        }
    }

    record Request(UUID ownerId, UUID requestId) {}

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DeletionReceiptExpiry receipts;
    @Autowired DeletionTtlEraser deletionTtl;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> created = new ArrayList<>();
    private final List<UUID> drafts = new ArrayList<>();
    private ch.qos.logback.classic.Logger alarmLogger;
    private ListAppender<ILoggingEvent> alarms;

    @BeforeEach
    void captureAlarms() {
        alarmLogger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(OpsAlarm.class.getName());
        alarms = new ListAppender<>();
        alarms.start();
        alarmLogger.addAppender(alarms);
    }

    @AfterEach
    void removeWhatThisTestCreated() {
        alarmLogger.detachAppender(alarms);
        alarms.stop();
        for (UUID draft : drafts) {
            jdbc.update("DELETE FROM itinerary_import_drafts WHERE id=?", draft);
        }
        drafts.clear();
        for (Request request : created) {
            // The worker is off, so each deletion job is still READY and would be claimed by a later class.
            jdbc.update("DELETE FROM background_jobs WHERE deduplication_key=?", "owner:" + request.ownerId());
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id=?", request.ownerId());
            jdbc.update("DELETE FROM demo_sessions WHERE owner_id=?", request.ownerId());
            jdbc.update("DELETE FROM deletion_tombstones WHERE owner_id=?", request.ownerId());
            jdbc.update("DELETE FROM deletion_requests WHERE owner_id=?", request.ownerId());
            jdbc.update("DELETE FROM owners WHERE id=?", request.ownerId());
        }
        created.clear();
    }

    @Test
    @DisplayName("BA-072-T6 a receipt that expires before its deletion finished logs one DELETION_RECEIPT_EXPIRED_UNFINISHED line, once, without an id")
    void anUnfinishedReceiptIsReportedOnceWhenItExpires() throws Exception {
        Request accepted = requestDeletion();
        Request running = withState(requestDeletion(), "RUNNING", 3);
        Request partial = withState(requestDeletion(), "PARTIAL_FAILED", 2);
        Request completed = withState(requestDeletion(), "COMPLETED", 1);
        Request failed = withState(requestDeletion(), "FAILED", 5);
        List<UUID> mine = List.of(accepted.requestId(), running.requestId(), partial.requestId(),
                completed.requestId(), failed.requestId());
        Instant now = expiresAt(mine);

        int before = alarms.list.size();
        List<ExpiredReceipt> first = receipts.expire(now);
        List<ILoggingEvent> firstLines = linesOnThisThreadSince(before);

        Map<UUID, ExpiredReceipt> expiredMine = first.stream()
                .filter(receipt -> mine.contains(receipt.requestId()))
                .collect(Collectors.toMap(ExpiredReceipt::requestId, receipt -> receipt));
        assertThat(first.stream().filter(receipt -> mine.contains(receipt.requestId())))
                .as("each of this test's receipts expired exactly once").hasSize(5);
        assertThat(expiredMine.values()).extracting(ExpiredReceipt::status, ExpiredReceipt::attempt)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("ACCEPTED", 0),
                        org.assertj.core.groups.Tuple.tuple("RUNNING", 3),
                        org.assertj.core.groups.Tuple.tuple("PARTIAL_FAILED", 2),
                        org.assertj.core.groups.Tuple.tuple("COMPLETED", 1),
                        org.assertj.core.groups.Tuple.tuple("FAILED", 5));
        // One line per unfinished receipt this call expired - other classes' rows included - and no more.
        assertThat(firstLines).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactlyElementsOf(expectedLines(first));
        assertThat(firstLines).extracting(ILoggingEvent::getFormattedMessage).contains(
                OpsAlarm.deletionReceiptExpiredUnfinished("ACCEPTED", 0).line(),
                OpsAlarm.deletionReceiptExpiredUnfinished("RUNNING", 3).line(),
                OpsAlarm.deletionReceiptExpiredUnfinished("PARTIAL_FAILED", 2).line());
        // Not derived from the production predicate: a finished deletion is never reported.
        assertThat(firstLines).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains(" status=COMPLETED ") || line.contains(" status=FAILED "));
        assertThat(firstLines).allSatisfy(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage()).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-");
        });

        int between = alarms.list.size();
        List<ExpiredReceipt> second = receipts.expire(now);
        assertThat(second).extracting(ExpiredReceipt::requestId).as("a receipt expires once")
                .doesNotContainAnyElementsOf(mine);
        assertThat(linesOnThisThreadSince(between)).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactlyElementsOf(expectedLines(second));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deletion_requests WHERE id = ANY(?)"
                + " AND status_token_hash IS NOT NULL", Integer.class, (Object) mine.toArray(UUID[]::new))).isZero();
    }

    @Test
    @DisplayName("BA-072-T8 a receipt held by a deletion attempt is skipped, not waited for, and reported by the next sweep")
    void aHeldReceiptIsSkippedAndReportedByTheNextSweep() throws Exception {
        Request running = withState(requestDeletion(), "RUNNING", 1);
        Instant now = expiresAt(List.of(running.requestId()));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM deletion_requests WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, running.requestId());
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            try {
                List<ExpiredReceipt> whileHeld = CompletableFuture.supplyAsync(() -> receipts.expire(now))
                        .get(5, TimeUnit.SECONDS);
                assertThat(whileHeld).extracting(ExpiredReceipt::requestId).doesNotContain(running.requestId());
            } finally {
                holder.rollback();
            }
        }

        int before = alarms.list.size();
        List<ExpiredReceipt> next = receipts.expire(now);
        assertThat(next).extracting(ExpiredReceipt::requestId).containsOnlyOnce(running.requestId());
        assertThat(linesOnThisThreadSince(before)).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactlyElementsOf(expectedLines(next))
                .contains(OpsAlarm.deletionReceiptExpiredUnfinished("RUNNING", 1).line());
    }

    @Test
    @DisplayName("a due owner is not hard deleted while its receipt is held, so the receipt is still reported")
    void aHeldReceiptKeepsItsOwnerUntilItIsReported() throws Exception {
        Request running = withState(requestDeletion(), "RUNNING", 1);
        // Only the receipt stands between this owner and the hard delete: its replay rows are gone.
        jdbc.update("DELETE FROM idempotency_records WHERE owner_id=?", running.ownerId());
        jdbc.update("DELETE FROM demo_sessions WHERE owner_id=?", running.ownerId());
        Instant retained = jdbc.queryForObject("SELECT retain_until FROM deletion_tombstones WHERE owner_id=?",
                OffsetDateTime.class, running.ownerId()).toInstant();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM deletion_requests WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, running.requestId());
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }
            try {
                CompletableFuture.supplyAsync(() -> deletionTtl.erase(retained)).get(5, TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        }
        assertThat(ownerRows(running)).as("skipped by the expiry, so kept by the hard delete").isOne();

        int before = alarms.list.size();
        deletionTtl.erase(retained);
        assertThat(linesOnThisThreadSince(before)).extracting(ILoggingEvent::getFormattedMessage)
                .contains(OpsAlarm.deletionReceiptExpiredUnfinished("RUNNING", 1).line());
        assertThat(ownerRows(running)).as("reported, then removed").isZero();
    }

    @Test
    @DisplayName("a hard delete that fails does not take back a receipt expiry already reported")
    void aFailedHardDeleteKeepsTheExpiry() throws Exception {
        Request running = withState(requestDeletion(), "RUNNING", 1);
        jdbc.update("DELETE FROM idempotency_records WHERE owner_id=?", running.ownerId());
        jdbc.update("DELETE FROM demo_sessions WHERE owner_id=?", running.ownerId());
        // References the owner without cascade, so the hard delete of this owner fails after the expiry ran.
        UUID draft = UUID.randomUUID();
        drafts.add(draft);
        jdbc.update("""
                INSERT INTO itinerary_import_drafts
                    (id, owner_id, status, version, structured_draft, unresolved_tokens,
                     confirmed_trip_id, confirmed_at, expires_at, created_at)
                VALUES (?, ?, 'NEEDS_REVIEW', 1, '{}'::jsonb, '[]'::jsonb, NULL, NULL, ?, ?)
                """, draft, running.ownerId(), Timestamp.from(Instant.parse("2099-01-02T00:00:00Z")),
                Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
        Instant retained = jdbc.queryForObject("SELECT retain_until FROM deletion_tombstones WHERE owner_id=?",
                OffsetDateTime.class, running.ownerId()).toInstant();

        int before = alarms.list.size();
        assertThatThrownBy(() -> deletionTtl.erase(retained)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status_token_hash IS NULL FROM deletion_requests WHERE id=?",
                Boolean.class, running.requestId())).as("the expiry committed on its own").isTrue();
        assertThat(linesOnThisThreadSince(before)).extracting(ILoggingEvent::getFormattedMessage)
                .contains(OpsAlarm.deletionReceiptExpiredUnfinished("RUNNING", 1).line());

        int between = alarms.list.size();
        assertThatThrownBy(() -> deletionTtl.erase(retained)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(linesOnThisThreadSince(between)).as("nothing expired again, so nothing is reported again")
                .isEmpty();
    }

    private int ownerRows(Request request) {
        return jdbc.queryForObject("SELECT count(*) FROM owners WHERE id=?", Integer.class, request.ownerId());
    }

    private Request requestDeletion() throws Exception {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        String body = mvc.perform(delete("/api/v1/session")
                        .cookie(new Cookie("__Host-nullnull_session", bootstrap.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        Request request = new Request(bootstrap.owner.id(),
                UUID.fromString(json.readTree(body).get("requestId").asString()));
        created.add(request);
        return request;
    }

    private Request withState(Request request, String state, int attempt) {
        jdbc.update("UPDATE deletion_requests SET status=?, attempt_count=?,"
                + " completed_at=CASE WHEN ? IN ('COMPLETED','FAILED') THEN updated_at ELSE NULL END WHERE id=?",
                state, attempt, state, request.requestId());
        return request;
    }

    /** When this test's receipts are due: the latest of their expiries, all minted on one fixed clock. */
    private Instant expiresAt(List<UUID> requests) {
        return jdbc.queryForObject("SELECT max(status_token_expires_at) FROM deletion_requests WHERE id = ANY(?)",
                OffsetDateTime.class, (Object) requests.toArray(UUID[]::new)).toInstant();
    }

    private static List<String> expectedLines(List<ExpiredReceipt> expired) {
        return expired.stream().filter(ExpiredReceipt::unfinished)
                .map(receipt -> OpsAlarm.deletionReceiptExpiredUnfinished(receipt.status(), receipt.attempt()).line())
                .toList();
    }

    private List<ILoggingEvent> linesOnThisThreadSince(int index) {
        List<ILoggingEvent> snapshot;
        synchronized (alarms) {
            snapshot = List.copyOf(alarms.list.subList(index, alarms.list.size()));
        }
        String thread = Thread.currentThread().getName();
        return snapshot.stream()
                .filter(event -> event.getFormattedMessage().startsWith(
                        OpsAlarm.Name.DELETION_RECEIPT_EXPIRED_UNFINISHED.phrase() + " "))
                .filter(event -> thread.equals(event.getThreadName()))
                .toList();
    }
}
