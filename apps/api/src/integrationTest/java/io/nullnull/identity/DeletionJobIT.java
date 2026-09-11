package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.OwnerDataEraser;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"nullnull.jobs.enabled=true", "nullnull.jobs.poll-interval=PT0.02S",
        "nullnull.jobs.retry-backoff=PT1S", "nullnull.jobs.max-retry-backoff=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, DeletionJobIT.Config.class})
@Tag("identity-safety")
class DeletionJobIT {
    @TestConfiguration static class Config {
        @Bean FlakyEraser flakyEraser() { return new FlakyEraser(); }
    }
    static final class FlakyEraser implements OwnerDataEraser {
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        @Override public String name() { return "test-transient-failure"; }
        @Override public Set<String> ownerIdTables() { return Set.of(); }
        @Override public void erase(UUID ownerId, Instant deleteBefore) {
            if (!armed.get()) return;
            int concurrent = active.incrementAndGet();
            maxActive.accumulateAndGet(concurrent, Math::max);
            try {
                if (calls.incrementAndGet() == 1) throw new IllegalStateException("synthetic failure");
            } finally { active.decrementAndGet(); }
        }
    }
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FlakyEraser eraser;

    @Test
    @DisplayName("BA-012-T2 REC-SEC-03 partial deletion retries without overlapping or resurrecting owner data")
    void partialFailureRetriesToCompletion() throws Exception {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        Cookie cookie = new Cookie("__Host-nullnull_session", bootstrap.cookie);
        String key = "delete-" + UUID.randomUUID();
        eraser.armed.set(true);
        String first = mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitCompleted(bootstrap.owner.id());
        String replay = mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(eraser.calls).hasValue(2);
        assertThat(eraser.maxActive).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM deletion_requests WHERE owner_id=?",
                Integer.class, bootstrap.owner.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locale FROM owners WHERE id=?", String.class,
                bootstrap.owner.id())).isEqualTo("ko-KR");
        mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("Idempotency-Key", "new-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
    private void awaitCompleted(UUID ownerId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String state = jdbc.queryForObject("SELECT status FROM deletion_requests WHERE owner_id=?",
                    String.class, ownerId);
            if ("COMPLETED".equals(state)) return;
            Thread.sleep(25);
        }
        fail("deletion job did not complete within the test deadline");
    }
}
