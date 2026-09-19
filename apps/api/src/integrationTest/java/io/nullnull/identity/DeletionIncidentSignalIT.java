package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.OwnerDataEraser;
import io.nullnull.identity.application.SessionService;
import io.nullnull.operations.application.OpsAlarm;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-072: a deletion attempt that fails leaves one operator line per failure it records - a partial
 * failure while attempts are left, a final one on the last - carrying the job id and nothing that names
 * the owner or the request. The real worker runs the real handler; only the eraser is scripted, per owner,
 * because the shared database can hand this worker a deletion job an earlier class left READY.
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

    private final ObjectMapper json = new ObjectMapper();
    private final List<Deletion> created = new ArrayList<>();
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

    private Deletion requestDeletion(int failures) throws Exception {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        UUID ownerId = bootstrap.owner.id();
        eraser.failuresLeft.put(ownerId, new AtomicInteger(failures));
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
