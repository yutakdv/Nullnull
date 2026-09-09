package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.nullnull.identity.application.*;
import io.nullnull.identity.infrastructure.persistence.SessionTtlEraser;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, SessionSafetyIT.Probe.class})
@Tag("identity-safety")
class SessionSafetyIT {
    @Autowired SessionService sessions;
    @Autowired SessionTtlEraser eraser;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    private static final String ORIGIN = "http://localhost:5173";
    @RestController
    static class Probe {
        @GetMapping("/identity-test/me")
        @NullnullOperation(id = "testOwner", security = Security.SESSION)
        Map<String, UUID> me(OwnerContext owner) { return Map.of("id", owner.ownerId()); }
        @PostMapping("/identity-test/change")
        @NullnullOperation(id = "testChange", security = {Security.SESSION, Security.CSRF})
        Map<String, UUID> change(OwnerContext owner) { return Map.of("id", owner.ownerId()); }
    }
    private SessionService.Bootstrap bootstrap() { return sessions.bootstrap(null, null, null); }
    private Cookie cookie(SessionService.Bootstrap b) { return new Cookie("__Host-nullnull_session", b.cookie); }
    private OwnerContext context(SessionService.Bootstrap b) { return sessions.resolve(b.cookie, false); }
    @Test @DisplayName("BA-010-T1 cookie-derived owners and session-specific CSRF reject forgery")
    void isolation() throws Exception {
        var a = bootstrap(); var b = bootstrap(); var c = bootstrap();
        for (var item : List.of(a,b,c)) {
            mvc.perform(get("/api/v1/identity-test/me").cookie(cookie(item)).param("ownerId", a.owner.id().toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(item.owner.id().toString()));
        }
        mvc.perform(get("/api/v1/identity-test/me")).andExpect(status().isUnauthorized());
        for (String token : List.of("invalid", b.csrf.token, c.csrf.token)) {
            mvc.perform(post("/api/v1/identity-test/change").cookie(cookie(a)).header("Origin", ORIGIN)
                    .header("X-CSRF-Token", token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/identity-test/change").cookie(cookie(a)).header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/identity-test/change").cookie(cookie(a)).header("Origin", ORIGIN)
                .header("X-CSRF-Token", a.csrf.token)).andExpect(status().isOk());
        jdbc.update("UPDATE owners SET deleted_at = now() WHERE id = ?", a.owner.id());
        mvc.perform(get("/api/v1/identity-test/me").cookie(cookie(a))).andExpect(status().isUnauthorized());
    }
    @Test @DisplayName("BA-010-T1 same origin validates scheme host port credentials and repeated headers")
    void origins() throws Exception {
        for (String origin : List.of("https://localhost:5173", "http://localhost:5174", "http://localhost.evil:5173",
                "http://user@localhost:5173", "null", ORIGIN + "/path", ORIGIN + "?x=1", ORIGIN + " http://evil")) {
            mvc.perform(post("/api/v1/demo/sessions").header("Origin", origin))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        mvc.perform(post("/api/v1/demo/sessions")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/sessions").header("Origin", ORIGIN, ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/sessions").header("Referer", ORIGIN + "/welcome?tab=1"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/demo/sessions").header("Origin", "null").header("Referer", ORIGIN + "/"))
                .andExpect(status().isForbidden());
    }
    @Test @DisplayName("BA-010-T2 five tabs remain valid and sixth evicts least recently used")
    void lru() {
        var b = bootstrap(); var ctx = context(b);
        List<String> tokens = new ArrayList<>(); tokens.add(b.csrf.token);
        for (int i=0;i<4;i++) { tokens.add(sessions.issueCsrf(ctx).token); }
        for (String token : tokens) { sessions.authorize(ctx, token, true); }
        // Explicit clock order: token 1 is oldest, token 0 most recently used.
        jdbc.update("UPDATE demo_session_csrf_tokens SET last_used_at = created_at WHERE demo_session_id = ?",ctx.sessionId());
        sessions.authorize(ctx, tokens.getFirst(), true);
        String sixth = sessions.issueCsrf(ctx).token;
        assertThatThrownBy(() -> sessions.authorize(ctx,tokens.get(1),true)).isInstanceOf(ApiException.class);
        for (String token : List.of(tokens.get(0),tokens.get(2),tokens.get(3),tokens.get(4),sixth)) {
            sessions.authorize(ctx,token,true);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_session_csrf_tokens WHERE demo_session_id = ?",
                Integer.class,ctx.sessionId())).isEqualTo(5);
        jdbc.update("UPDATE demo_session_csrf_tokens SET expires_at = ? WHERE demo_session_id = ?",Timestamp.from(clock.instant().minusSeconds(1)),ctx.sessionId());
        assertThatThrownBy(() -> sessions.authorize(ctx,sixth,true)).isInstanceOf(ApiException.class);
        sessions.authorize(ctx,sessions.issueCsrf(ctx).token,true);
    }
    @Test @DisplayName("BA-010-T2 concurrent tab issuance retains exactly five token hashes")
    void concurrentTokens() throws Exception {
        var b=bootstrap(); var ctx=context(b);
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Callable<String>> jobs=new ArrayList<>();
            for(int i=0;i<12;i++) { jobs.add(() -> sessions.issueCsrf(ctx).token); }
            var results=executor.invokeAll(jobs);
            long valid=0;
            for(var result:results) {
                String token=result.get(30,TimeUnit.SECONDS);
                try { sessions.authorize(ctx,token,true); valid++; } catch(ApiException ignored) { }
            }
            assertThat(valid).isEqualTo(5);
        }
    }
    @Test @DisplayName("BA-010-T3 bootstrap retries preserve owner and first activity prevents orphan cleanup")
    void bootstrapAndOrphans() {
        var b=bootstrap(); var ctx=context(b);
        var resumed=sessions.bootstrap(b.cookie,null,null);
        assertThat(resumed.owner.id()).isEqualTo(b.owner.id()); assertThat(resumed.cookie).isNull();
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM demo_sessions WHERE id = ?",Timestamp.class,ctx.sessionId())).isNull();
        sessions.authorize(ctx,null,false);
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM demo_sessions WHERE id = ?",Timestamp.class,ctx.sessionId())).isNotNull();
        var orphan=bootstrap(); var orphanCtx=context(orphan);
        eraser.erase(clock.instant().plus(Duration.ofMinutes(15)).plusSeconds(1));
        assertThat(sessions.resolve(b.cookie,false).ownerId()).isEqualTo(b.owner.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?",Integer.class,orphan.owner.id())).isZero();
        assertThatThrownBy(() -> sessions.resolve(orphan.cookie,false)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_session_csrf_tokens WHERE demo_session_id = ?",Integer.class,orphanCtx.sessionId())).isZero();
    }
    @Test @DisplayName("BA-010-T3 idle absolute expiry and revoked cookies cannot authenticate")
    void expiration() {
        var b=bootstrap(); var ctx=context(b);
        jdbc.update("UPDATE demo_sessions SET expires_at = ? WHERE id = ?",Timestamp.from(clock.instant().minusSeconds(1)),ctx.sessionId());
        assertThatThrownBy(() -> sessions.resolve(b.cookie,false)).isInstanceOf(ApiException.class);
        assertThat(sessions.bootstrap(b.cookie,null,null).owner.id()).isNotEqualTo(b.owner.id());
        var absolute=bootstrap();var ac=context(absolute);
        jdbc.update("UPDATE demo_sessions SET created_at = ?, expires_at = ? WHERE id = ?",Timestamp.from(clock.instant().minus(Duration.ofDays(90)).minusSeconds(1)),Timestamp.from(clock.instant().plusSeconds(3600)),ac.sessionId());
        assertThatThrownBy(() -> sessions.resolve(absolute.cookie,false)).isInstanceOf(ApiException.class);
        var revoked=bootstrap();var rc=context(revoked);
        jdbc.update("UPDATE demo_sessions SET revoked_at = ? WHERE id = ?",Timestamp.from(clock.instant().minusSeconds(1)),rc.sessionId());
        assertThatThrownBy(() -> sessions.resolve(revoked.cookie,false)).isInstanceOf(ApiException.class);
        assertThat(sessions.resolve(revoked.cookie,true).revoked()).isTrue();
        jdbc.update("UPDATE demo_sessions SET revoked_at = ? WHERE id = ?",Timestamp.from(clock.instant().minus(Duration.ofHours(24))),rc.sessionId());
        assertThatThrownBy(() -> sessions.resolve(revoked.cookie,true)).isInstanceOf(ApiException.class);
    }
    @Autowired javax.sql.DataSource dataSource;
    @Test @DisplayName("BA-010-T2 session issuance waits for owner lifecycle and session locks")
    void lockContention() throws Exception {
        var b=bootstrap(); var ctx=context(b);
        for(String table:List.of("owners","demo_sessions")) {
            try(var connection=dataSource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
                connection.setAutoCommit(false);
                try(var statement=connection.prepareStatement("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1,table.equals("owners")?ctx.ownerId():ctx.sessionId());
                    statement.executeQuery().close();
                }
                if (table.equals("demo_sessions")) {
                    try(var update=connection.prepareStatement("UPDATE demo_sessions SET revoked_at = ? WHERE id = ?")) {
                        update.setTimestamp(1,Timestamp.from(clock.instant().minusSeconds(1)));
                        update.setObject(2,ctx.sessionId());update.executeUpdate();
                    }
                }
                var entered=new CountDownLatch(1);
                var result=executor.submit(() -> { entered.countDown();return sessions.issueCsrf(ctx); });
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try {
                    assertThatThrownBy(() -> result.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally { connection.commit(); }
                if (table.equals("demo_sessions")) {
                    assertThatThrownBy(() -> result.get(5,TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class);
                } else { assertThat(result.get(5,TimeUnit.SECONDS)).isNotNull(); }
            }
        }
    }
    @Test @DisplayName("BA-010-T1 duplicate cookies and repeated CSRF headers are rejected")
    void ambiguousCredentials() throws Exception {
        var a=bootstrap();var b=bootstrap();
        mvc.perform(get("/api/v1/identity-test/me").cookie(cookie(a),cookie(b))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/identity-test/change").cookie(cookie(a)).header("Origin",ORIGIN)
                .header("X-CSRF-Token",a.csrf.token,a.csrf.token)).andExpect(status().isForbidden());
    }
    @Test @DisplayName("BA-010-T3 revoked session retention preserves before 30 days and deletes at cutoff")
    void revokedRetention() {
        var b=bootstrap();var ctx=context(b);Instant revoked=clock.instant();
        jdbc.update("UPDATE demo_sessions SET revoked_at = ? WHERE id = ?",Timestamp.from(revoked),ctx.sessionId());
        eraser.erase(revoked.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_sessions WHERE id = ?",Integer.class,ctx.sessionId())).isEqualTo(1);
        eraser.erase(revoked.plus(Duration.ofDays(30)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_sessions WHERE id = ?",Integer.class,ctx.sessionId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?",Integer.class,ctx.ownerId())).isEqualTo(1);
    }

    @Test @DisplayName("BA-010-T1 database stores only SHA-256 hashes and enforces unique bounded credentials")
    void storedHashes() throws Exception {
        var b=bootstrap();var ctx=context(b);
        byte[] expected=java.security.MessageDigest.getInstance("SHA-256")
                .digest(b.cookie.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] actual=jdbc.queryForObject("SELECT token_hash FROM demo_sessions WHERE id = ?",byte[].class,ctx.sessionId());
        assertThat(java.util.Arrays.equals(actual,expected)).isTrue();
        assertThat(b.cookie.length()).isEqualTo(43);
        var hashes=jdbc.queryForList("SELECT token_hash FROM demo_session_csrf_tokens WHERE demo_session_id = ?",byte[].class,ctx.sessionId());
        byte[] csrf=java.security.MessageDigest.getInstance("SHA-256")
                .digest(b.csrf.token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(hashes).hasSize(1);assertThat(java.util.Arrays.equals(hashes.getFirst(),csrf)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_sessions SET token_hash = ? WHERE id = ?",new byte[31],ctx.sessionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var other=bootstrap();var oc=context(other);
        assertThatThrownBy(() -> jdbc.update("UPDATE demo_sessions SET token_hash = ? WHERE id = ?",expected,oc.sessionId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

}
