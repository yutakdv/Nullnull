package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.nullnull.identity.application.SessionService;
import io.nullnull.identity.application.TombstoneReapplier;
import io.nullnull.identity.application.OwnerDataEraser;
import io.nullnull.identity.application.ExpiredIdempotencyRecordEraser;
import io.nullnull.identity.infrastructure.persistence.DeletionTtlEraser;
import io.nullnull.identity.infrastructure.persistence.SessionTtlEraser;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "nullnull.jobs.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, DeletionIT.Time.class})
@Tag("identity-safety")
class DeletionIT {
    @TestConfiguration static class Time {
        @Bean @Primary MutableClock deletionClock() {
            return MutableClock.at(Instant.parse("2032-01-01T00:00:00.123456Z"));
        }
    }
    @org.springframework.beans.factory.annotation.Value("${spring.mvc.servlet.path}") String servletPath;
    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired TombstoneReapplier reapplier;
    @Autowired List<OwnerDataEraser> erasers;
    @Autowired DeletionTtlEraser deletionTtl;
    @Autowired SessionTtlEraser sessionTtl;
    @Autowired ExpiredIdempotencyRecordEraser idempotencyTtl;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("BA-012-T1 lost deletion response replays the same receipt without storing its bearer")
    void responseLossReplayAndStatusBearer() throws Exception {
        var bootstrap = sessions.bootstrap(null, null, null);
        Cookie cookie = new Cookie("__Host-nullnull_session", bootstrap.cookie);
        String key = "delete-" + UUID.randomUUID();
        var first = mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse();
        JsonNode body = json.readTree(first.getContentAsString());
        String token = body.get("statusToken").asText();
        String requestId = body.get("requestId").asText();
        assertThat(first.getHeader("Location")).isEqualTo(body.get("statusUrl").asText());
        // statusUrl is built from a literal in DeletionService, not from the configured servlet
        // path, so the two can drift apart the moment that setting changes. Pin them together.
        assertThat(body.get("statusUrl").asText())
                .as("statusUrl must start with the servlet path the app is actually served under")
                .startsWith(servletPath + "/deletion-requests/");
        assertThat(jdbc.queryForObject("SELECT bool_and(revoked_at IS NOT NULL) FROM demo_sessions WHERE owner_id=?",
                Boolean.class, bootstrap.owner.id())).as("accept must revoke every owner session").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_session_csrf_tokens c JOIN demo_sessions s"
                + " ON s.id=c.demo_session_id WHERE s.owner_id=?", Integer.class, bootstrap.owner.id()))
                .as("accept must delete every owner CSRF bearer").isZero();

        var replay = mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("Idempotency-Key", "different-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/session/csrf").cookie(cookie)
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/deletion-requests/{id}", requestId)
                        .header("X-Deletion-Status-Token", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                // Still moving, so the caller is told when to look again rather than guessing.
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.requestId").value(requestId));
        var wrongToken = mvc.perform(get("/api/v1/deletion-requests/{id}", requestId)
                        .header("X-Deletion-Status-Token", token.substring(1) + "A"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();
        // An unknown id and a token that does not verify must be one answer. If they differed, a
        // caller could probe which deletion requests exist by reading the difference.
        var unknownId = mvc.perform(get("/api/v1/deletion-requests/{id}", UUID.randomUUID())
                        .header("X-Deletion-Status-Token", token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();
        assertThat(json.readTree(unknownId.getContentAsString()).get("code").asText())
                .as("unknown id and wrong token must answer with the same code")
                .isEqualTo(json.readTree(wrongToken.getContentAsString()).get("code").asText())
                .isEqualTo("NOT_FOUND");
        assertThat(json.readTree(unknownId.getContentAsString()).get("detail").asText())
                .as("neither may say which of the two it was")
                .isEqualTo(json.readTree(wrongToken.getContentAsString()).get("detail").asText());

        jdbc.update("UPDATE deletion_requests SET status_token_hash=decode(repeat('00',32),'hex') WHERE id=?",
                UUID.fromString(requestId));
        mvc.perform(get("/api/v1/deletion-requests/{id}", requestId)
                        .header("X-Deletion-Status-Token", token))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM owners WHERE id=?",
                Boolean.class, bootstrap.owner.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT bool_and(revoked_at IS NOT NULL) FROM demo_sessions WHERE owner_id=?",
                Boolean.class, bootstrap.owner.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_session_csrf_tokens c JOIN demo_sessions s"
                + " ON s.id=c.demo_session_id WHERE s.owner_id=?", Integer.class, bootstrap.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT position(? in response_body::text) FROM idempotency_records"
                + " WHERE owner_id=? AND route_key='DELETE /session'", Integer.class, token, bootstrap.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT position(? in payload_reference::text) FROM background_jobs"
                + " WHERE deduplication_key=?", Integer.class, token, "owner:" + bootstrap.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT octet_length(status_token_hash) FROM deletion_requests WHERE id=?",
                Integer.class, UUID.fromString(requestId))).isEqualTo(32);

        clock.advance(Duration.ofDays(7));
        mvc.perform(get("/api/v1/deletion-requests/{id}", requestId)
                        .header("X-Deletion-Status-Token", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DELETION_STATUS_EXPIRED"));
    }

    @Test
    @DisplayName("BA-012-T3 a retained tombstone reapplies deletion before the web lifecycle phase")
    void tombstoneReappliesRestoredOwnerData() throws Exception {
        var bootstrap = sessions.bootstrap(null, "en-US", "Europe/Paris");
        Cookie cookie = new Cookie("__Host-nullnull_session", bootstrap.cookie);
        mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isAccepted());
        jdbc.update("UPDATE owners SET locale='en-US',timezone='Europe/Paris',onboarding_completed=true"
                + " WHERE id=?", bootstrap.owner.id());
        assertThat(reapplier.getPhase()).isLessThan(Integer.MAX_VALUE - 1);
        reapplier.stop();
        reapplier.start();
        var restored = jdbc.queryForMap("SELECT locale,timezone,onboarding_completed FROM owners WHERE id=?",
                bootstrap.owner.id());
        assertThat(restored).containsEntry("locale", "ko-KR").containsEntry("timezone", "UTC")
                .containsEntry("onboarding_completed", false);
    }

    @Test
    @DisplayName("BA-012-T2 every table holding an owner reference is erased by its module or retained for a named reason")
    void ownerIdTableCoverageIsExplicit() {
        Map<String,String> retained = Map.of(
                "demo_sessions", "revoked cookie supports 24h replay and hash is removed by 30d session TTL",
                "idempotency_records", "DELETE /session receipt projection supports 24h replay",
                "deletion_requests", "status receipt is retained after bearer expiry",
                "deletion_tombstones", "restore deletion manifest is retained through backup recovery",
                "source_registry_revisions", "reviewed_by_owner_id records who approved a provider"
                        + " contract revision; no row sets it today and an operator approval is an"
                        + " audit fact about the registry, not the traveller's own data");
        var covered = new HashSet<>(retained.keySet());
        erasers.forEach(eraser -> covered.addAll(eraser.ownerIdTables()));
        // Found by the FOREIGN KEY to owners, not by the column being called owner_id. The name-based
        // sweep this replaced missed source_registry_revisions.reviewed_by_owner_id from V007 and
        // posts.author_owner_id from V015 - an owner identifier escaped the guarantee simply by
        // being spelled differently, which is the one thing a coverage check must not permit.
        List<String> actual = jdbc.queryForList("""
                SELECT DISTINCT source.relname
                  FROM pg_constraint constraint_
                  JOIN pg_class source ON source.oid = constraint_.conrelid
                  JOIN pg_class target ON target.oid = constraint_.confrelid
                  JOIN pg_namespace space ON space.oid = source.relnamespace
                 WHERE constraint_.contype = 'f' AND target.relname = 'owners'
                   AND space.nspname = 'public'
                 ORDER BY source.relname
                """, String.class);
        assertThat(actual).as("tables referencing owners").isNotEmpty();
        assertThat(actual).allMatch(covered::contains);
        assertThat(retained.values()).allMatch(reason -> !reason.isBlank());
    }

    @Test
    @DisplayName("BA-012-T3 receipt bearer expires at seven days and owner waits for retained replay rows")
    void ttlDoesNotBreakReplayForeignKeys() throws Exception {
        var bootstrap = sessions.bootstrap(null, null, null);
        Cookie cookie = new Cookie("__Host-nullnull_session", bootstrap.cookie);
        mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isAccepted());
        Instant accepted = clock.instant();
        deletionTtl.erase(accepted.plus(Duration.ofDays(21)));
        assertThat(jdbc.queryForObject("SELECT status_token_hash IS NULL FROM deletion_requests WHERE owner_id=?",
                Boolean.class, bootstrap.owner.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id=?", Integer.class,
                bootstrap.owner.id())).isOne();
        sessionTtl.erase(accepted.plus(Duration.ofDays(30)));
        idempotencyTtl.erase(accepted.plus(Duration.ofDays(30)));
        deletionTtl.erase(accepted.plus(Duration.ofDays(30)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id=?", Integer.class,
                bootstrap.owner.id())).isZero();
    }

    @Test
    @DisplayName("BA-012-T1 enqueue failure rolls back revoke receipt tombstone and owner deletion together")
    void enqueueFailureRollsBackWholeCommand() throws Exception {
        var bootstrap = sessions.bootstrap(null, null, null);
        Cookie cookie = new Cookie("__Host-nullnull_session", bootstrap.cookie);
        Instant now = clock.instant();
        jdbc.update("INSERT INTO background_jobs(id,type,deduplication_key,status,max_attempts,"
                        + "next_attempt_at,created_at) VALUES (?,'other-job',?,'READY',1,?,?)",
                UUID.randomUUID(), "owner:" + bootstrap.owner.id(), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        mvc.perform(delete("/api/v1/session").cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", bootstrap.csrf.token)
                        .header("Idempotency-Key", "delete-" + UUID.randomUUID()))
                .andExpect(status().isInternalServerError());
        mvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM owners WHERE id=?",
                Boolean.class, bootstrap.owner.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT bool_and(revoked_at IS NULL) FROM demo_sessions WHERE owner_id=?",
                Boolean.class, bootstrap.owner.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deletion_requests WHERE owner_id=?",
                Integer.class, bootstrap.owner.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_id=?"
                + " AND route_key='DELETE /session'", Integer.class, bootstrap.owner.id())).isZero();
    }
}
