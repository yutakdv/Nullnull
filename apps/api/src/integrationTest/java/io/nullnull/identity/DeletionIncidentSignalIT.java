package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.DeletionRecord;
import io.nullnull.identity.application.DeletionStore;
import io.nullnull.identity.application.ExpiredReceipt;
import io.nullnull.identity.application.OwnerDataEraser;
import io.nullnull.identity.application.SessionService;
import io.nullnull.identity.infrastructure.persistence.JdbcDeletionStore;
import io.nullnull.operations.application.OpsAlarm;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-072 and BA-012: a deletion attempt that fails leaves one operator line per failure it records - a
 * partial failure while attempts are left, a final one on the last or from the dead-letter hook - carrying
 * the job id and nothing that names the owner or the request, and a dead-lettered deletion ends FAILED.
 * The real worker runs the real handler. What is scripted, per owner so that a deletion job an earlier
 * class left READY in the shared database is not affected: the eraser's failures, and the store's
 * markFailed, which throws on every attempt for the owners named in ScriptedDeletionStore (the shape of
 * the defect where markFailed never succeeded). Dead-letter states are seeded as rows.
 */
@SpringBootTest(properties = {"nullnull.jobs.enabled=true", "nullnull.jobs.poll-interval=PT0.02S",
        "nullnull.jobs.retry-backoff=PT1S", "nullnull.jobs.max-retry-backoff=PT1S",
        "nullnull.deletion.retry-limit=2"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        DeletionIncidentSignalIT.Config.class})
@Tag("identity-safety")
@DisplayName("BA-072 deletion incident signals")
class DeletionIncidentSignalIT {

    private static final String FAILURE = "OWNER_DATA_ERASE_FAILED";
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @TestConfiguration static class Config {
        @Bean ScriptedEraser scriptedEraser() { return new ScriptedEraser(); }
        @Bean @Primary ScriptedDeletionStore scriptedDeletionStore(JdbcDeletionStore real) {
            return new ScriptedDeletionStore(real);
        }
    }

    /**
     * The real store, except that recording a failure throws, on every attempt, for the named owners - the
     * shape of the defect BA-072 found, where markFailed threw on every call. Such a failure escapes the
     * handler, so the worker's own failure path retries it and, on the last attempt, dead-letters the job
     * and calls the hook. Every other caller of DeletionStore in this context goes through it unchanged.
     */
    static final class ScriptedDeletionStore implements DeletionStore {
        final Set<UUID> failRecordingFor = ConcurrentHashMap.newKeySet();
        private final DeletionStore real;
        ScriptedDeletionStore(DeletionStore real) { this.real = real; }
        @Override public void markFailed(UUID requestId, int attempt, String status, String failureCode, Instant now) {
            if (real.find(requestId).map(request -> failRecordingFor.contains(request.ownerId())).orElse(false)) {
                throw new IllegalStateException("synthetic failure while recording a failed attempt");
            }
            real.markFailed(requestId, attempt, status, failureCode, now);
        }
        @Override public void create(DeletionRecord request, byte[] statusTokenHash, UUID tombstoneId,
                Instant deleteBefore, Instant retainUntil, String scopeHash) {
            real.create(request, statusTokenHash, tombstoneId, deleteBefore, retainUntil, scopeHash);
        }
        @Override public Optional<DeletionRecord> find(UUID requestId) { return real.find(requestId); }
        @Override public boolean hasStatusTokenHash(UUID requestId, byte[] hash) {
            return real.hasStatusTokenHash(requestId, hash);
        }
        @Override public void markRunning(UUID requestId, int attempt, Instant now) {
            real.markRunning(requestId, attempt, now);
        }
        @Override public void markCompleted(UUID requestId, Instant now) { real.markCompleted(requestId, now); }
        @Override public boolean failUnfinished(UUID requestId, int attempt, String failureCode, Instant now) {
            return real.failUnfinished(requestId, attempt, failureCode, now);
        }
        @Override public List<UUID> tombstonedOwners() { return real.tombstonedOwners(); }
        @Override public List<ExpiredReceipt> expireStatusTokens(Instant now) { return real.expireStatusTokens(now); }
        @Override public int hardDeleteEligibleOwners(Instant now) { return real.hardDeleteEligibleOwners(now); }
    }

    /** Fails the named owners as many times as scripted and leaves every other owner alone. */
    static final class ScriptedEraser implements OwnerDataEraser {
        final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
        @Override public String name() { return "test-scripted-failure"; }
        @Override public Set<String> ownerIdTables() { return Set.of(); }
        @Override public void erase(UUID ownerId, Instant deleteBefore) {
            AtomicInteger left = failuresLeft.get(ownerId);
            if (left != null && left.getAndDecrement() > 0) {
                throw new IllegalStateException("synthetic erase failure");
            }
        }
    }

    record Deletion(UUID ownerId, UUID requestId, UUID jobId) {}

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScriptedEraser eraser;
    @Autowired ScriptedDeletionStore store;

    private final ObjectMapper json = new ObjectMapper();
    private final List<Deletion> created = new ArrayList<>();
    private final List<UUID> jobs = new ArrayList<>();
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
        for (UUID job : jobs) {
            jdbc.update("DELETE FROM background_jobs WHERE id=?", job);
        }
        jobs.clear();
        for (Deletion deletion : created) {
            jdbc.update("DELETE FROM background_jobs WHERE id=?", deletion.jobId());
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id=?", deletion.ownerId());
            jdbc.update("DELETE FROM demo_sessions WHERE owner_id=?", deletion.ownerId());
            jdbc.update("DELETE FROM deletion_tombstones WHERE owner_id=?", deletion.ownerId());
            jdbc.update("DELETE FROM deletion_requests WHERE owner_id=?", deletion.ownerId());
            jdbc.update("DELETE FROM owners WHERE id=?", deletion.ownerId());
        }
        created.clear();
    }

    @Test
    @DisplayName("BA-072-T2 a recorded partial deletion failure logs one DELETION_PARTIAL_FAILED line with the job id only")
    void aPartialFailureLogsOneLine() throws Exception {
        Deletion deletion = requestDeletion(1);
        awaitDeletionStatus(deletion, "COMPLETED");

        List<ILoggingEvent> lines = awaitAlarms(OpsAlarm.Name.DELETION_PARTIAL_FAILED, deletion);
        assertThat(lines).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(OpsAlarm.deletionPartialFailed(deletion.jobId(), 1, FAILURE).line());
        assertOperatorSafe(lines.getFirst(), deletion, Level.WARN);
    }

    @Test
    @DisplayName("BA-072-T4 a recorded final deletion failure logs one DELETION_FAILED line with the job id only")
    void aFinalFailureLogsOneLine() throws Exception {
        Deletion deletion = requestDeletion(Integer.MAX_VALUE);
        awaitJobStatus(deletion, "FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM deletion_requests WHERE id=?", String.class,
                deletion.requestId())).isEqualTo("FAILED");

        List<ILoggingEvent> lines = awaitAlarms(OpsAlarm.Name.DELETION_FAILED, deletion);
        assertThat(lines).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(OpsAlarm.deletionFailed(deletion.jobId(), 2, FAILURE).line());
        assertOperatorSafe(lines.getFirst(), deletion, Level.ERROR);
    }

    @Test
    @DisplayName("BA-012-T4 a deletion job dead-lettered on its last attempt ends its request FAILED from each unfinished state")
    void aDeadLetteredDeletionEndsItsRequestFailed() {
        // Each state a request can be in when its last attempt dies: never started (the worker died before
        // markRunning committed), running, or back from a recorded partial failure.
        for (String state : List.of("ACCEPTED", "RUNNING", "PARTIAL_FAILED")) {
            Deletion deletion = abandonedOnItsLastAttempt(state);
            awaitJobStatus(deletion, "FAILED");
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM background_jobs WHERE id=?", String.class,
                    deletion.jobId())).isEqualTo("LEASE_EXPIRED");
            // The hook writes in the dead letter's own transaction, so the request is final once the job is.
            Map<String, Object> request = jdbc.queryForMap(
                    "SELECT status, failure_code, completed_at FROM deletion_requests WHERE id=?", deletion.requestId());
            assertThat(request.get("status")).as("from %s", state).isEqualTo("FAILED");
            assertThat(request.get("failure_code")).as("from %s", state).isEqualTo(FAILURE);
            assertThat(request.get("completed_at")).as("FAILED is terminal and says when it stopped").isNotNull();
        }
    }

    @Test
    @DisplayName("BA-012-T4 BA-072-T9 a last attempt whose failure could not be recorded is ended FAILED by the hook on the worker's failure path")
    void anUnrecordedLastFailureIsEndedByTheHook() throws Exception {
        Deletion deletion = requestDeletion(Integer.MAX_VALUE, true);
        awaitJobStatus(deletion, "FAILED");
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT attempt_count, last_error_code FROM background_jobs WHERE id=?", deletion.jobId());
        assertThat(job.get("last_error_code")).as("the failure escaped the handler").isEqualTo("HANDLER_ERROR");
        assertThat(job.get("attempt_count")).isEqualTo(2);
        Map<String, Object> request = jdbc.queryForMap(
                "SELECT status, failure_code FROM deletion_requests WHERE id=?", deletion.requestId());
        assertThat(request.get("status")).isEqualTo("FAILED");
        assertThat(request.get("failure_code")).isEqualTo(FAILURE);

        List<ILoggingEvent> lines = awaitAlarms(OpsAlarm.Name.DELETION_FAILED, deletion);
        assertThat(lines).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(OpsAlarm.deletionFailed(deletion.jobId(), 2, "HANDLER_ERROR").line());
        assertThat(alarms(OpsAlarm.Name.DELETION_PARTIAL_FAILED, deletion))
                .as("no partial failure was ever recorded").isEmpty();
    }

    @Test
    @DisplayName("the dead-letter hook leaves a request that already COMPLETED as it is and logs nothing for it")
    void theHookLeavesACompletedRequestAlone() {
        Deletion completed = abandonedOnItsLastAttempt("COMPLETED");
        // Seeded after it, so the sweep that ends this one has already passed the completed one.
        Deletion control = abandonedOnItsLastAttempt("RUNNING");
        awaitAlarms(OpsAlarm.Name.DELETION_FAILED, control);
        awaitJobStatus(completed, "FAILED");

        Map<String, Object> request = jdbc.queryForMap(
                "SELECT status, failure_code FROM deletion_requests WHERE id=?", completed.requestId());
        assertThat(request.get("status")).isEqualTo("COMPLETED");
        assertThat(request.get("failure_code")).isNull();
        assertThat(alarms(OpsAlarm.Name.DELETION_FAILED, completed)).isEmpty();
    }

    @Test
    @DisplayName("BA-072-T9 a deletion the dead-letter hook ends FAILED logs one DELETION_FAILED line with the job's error code")
    void aDeadLetteredDeletionLogsOneLine() {
        Deletion deletion = abandonedOnItsLastAttempt("RUNNING");
        List<ILoggingEvent> lines = awaitAlarms(OpsAlarm.Name.DELETION_FAILED, deletion);
        assertThat(lines).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(OpsAlarm.deletionFailed(deletion.jobId(), 1, "LEASE_EXPIRED").line());
        assertOperatorSafe(lines.getFirst(), deletion, Level.ERROR);
    }

    @Test
    @DisplayName("BA-005-T5 a deletion job whose payload cannot be read is dead-lettered on its first attempt, not retried")
    void anUnreadablePayloadIsNotRetried() {
        UUID job = UUID.randomUUID();
        jobs.add(job);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO background_jobs (id, type, deduplication_key, status, payload_reference,
                    attempt_count, max_attempts, next_attempt_at, created_at)
                VALUES (?, 'delete-owner-data', ?, 'READY', '{}'::jsonb, 0, 3, ?, ?)
                """, job, "test-unreadable:" + job, now, now);
        Awaitility.await().atMost(DEADLINE).until(() -> "FAILED".equals(jdbc.queryForObject(
                "SELECT status FROM background_jobs WHERE id=?", String.class, job)));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempt_count, last_error_code FROM background_jobs WHERE id=?", job);
        assertThat(row.get("last_error_code")).isEqualTo("INVALID_JOB_PAYLOAD");
        assertThat(row.get("attempt_count")).as("the next attempt would read the same payload").isEqualTo(1);
    }

    /**
     * A deletion whose worker died on its last attempt: the request is in {@code state} and the job is
     * RUNNING on a lease that ran out with no attempt left - what the abandoned sweep finds once a dead
     * process's connection, and with it the job row's lock, is gone. Seeded rather than produced, because a
     * hang inside a unit of work holds the job row and the sweep rightly skips a locked row until then.
     */
    private Deletion abandonedOnItsLastAttempt(String state) {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        UUID ownerId = bootstrap.owner.id();
        UUID requestId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO deletion_requests (id, owner_id, status, attempt_count, failure_code,
                    status_token_expires_at, requested_at, started_at, completed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, ownerId, state, "ACCEPTED".equals(state) ? 0 : 1,
                "PARTIAL_FAILED".equals(state) ? FAILURE : null, Timestamp.from(now.plus(Duration.ofDays(7))),
                Timestamp.from(now.minus(Duration.ofHours(1))),
                "ACCEPTED".equals(state) ? null : Timestamp.from(now.minus(Duration.ofHours(1))),
                "COMPLETED".equals(state) ? Timestamp.from(now.minus(Duration.ofMinutes(3))) : null,
                Timestamp.from(now.minus(Duration.ofHours(1))));
        Deletion deletion = new Deletion(ownerId, requestId, jobId);
        created.add(deletion);
        jdbc.update("""
                INSERT INTO background_jobs (id, type, deduplication_key, status, payload_reference,
                    attempt_count, max_attempts, next_attempt_at, locked_by, lease_until, heartbeat_at, created_at)
                VALUES (?, 'delete-owner-data', ?, 'RUNNING', CAST(? AS jsonb), 1, 1, ?, 'w-gone:token', ?, ?, ?)
                """, jobId, "owner:" + ownerId,
                "{\"ownerId\":\"" + ownerId + "\",\"requestId\":\"" + requestId + "\"}",
                Timestamp.from(now.minus(Duration.ofHours(1))), Timestamp.from(now.minus(Duration.ofMinutes(1))),
                Timestamp.from(now.minus(Duration.ofMinutes(2))), Timestamp.from(now.minus(Duration.ofHours(1))));
        return deletion;
    }

    private Deletion requestDeletion(int failures) throws Exception {
        return requestDeletion(failures, false);
    }

    private Deletion requestDeletion(int failures, boolean failRecording) throws Exception {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        UUID ownerId = bootstrap.owner.id();
        eraser.failuresLeft.put(ownerId, new AtomicInteger(failures));
        if (failRecording) {
            store.failRecordingFor.add(ownerId);
        }
        String body = mvc.perform(delete("/api/v1/session")
                        .cookie(new Cookie("__Host-nullnull_session", bootstrap.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(json.readTree(body).get("requestId").asString());
        UUID jobId = jdbc.queryForObject("SELECT id FROM background_jobs WHERE deduplication_key=?",
                UUID.class, "owner:" + ownerId);
        Deletion deletion = new Deletion(ownerId, requestId, jobId);
        created.add(deletion);
        return deletion;
    }

    private void awaitDeletionStatus(Deletion deletion, String expected) {
        Awaitility.await().atMost(DEADLINE).until(() -> expected.equals(jdbc.queryForObject(
                "SELECT status FROM deletion_requests WHERE id=?", String.class, deletion.requestId())));
    }

    private void awaitJobStatus(Deletion deletion, String expected) {
        Awaitility.await().atMost(DEADLINE).until(() -> expected.equals(jdbc.queryForObject(
                "SELECT status FROM background_jobs WHERE id=?", String.class, deletion.jobId())));
    }

    /** Waits for the first line, then answers every line so far: a second one would be a defect. */
    private List<ILoggingEvent> awaitAlarms(OpsAlarm.Name name, Deletion deletion) {
        Awaitility.await().atMost(DEADLINE).until(() -> !alarms(name, deletion).isEmpty());
        return alarms(name, deletion);
    }

    /** By name and by job id: the worker also runs deletion jobs other classes left behind. */
    private List<ILoggingEvent> alarms(OpsAlarm.Name name, Deletion deletion) {
        List<ILoggingEvent> snapshot;
        synchronized (alarms) {
            snapshot = List.copyOf(alarms.list);
        }
        return snapshot.stream()
                .filter(event -> event.getFormattedMessage().startsWith(name.phrase() + " "))
                .filter(event -> event.getFormattedMessage().contains(" jobId=" + deletion.jobId() + " "))
                .toList();
    }

    private static void assertOperatorSafe(ILoggingEvent line, Deletion deletion, Level level) {
        assertThat(line.getLevel()).isEqualTo(level);
        assertThat(line.getFormattedMessage())
                .doesNotContain(deletion.ownerId().toString())
                .doesNotContain(deletion.requestId().toString())
                .doesNotContain("owner:");
        assertThat(line.getThrowableProxy()).isNull();
        assertThat(line.getMDCPropertyMap().values()).doesNotContain(deletion.ownerId().toString(),
                deletion.requestId().toString());
    }
}
