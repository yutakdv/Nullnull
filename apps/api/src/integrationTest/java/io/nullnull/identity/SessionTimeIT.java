package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import io.nullnull.identity.application.*;
import io.nullnull.identity.infrastructure.persistence.SessionTtlEraser;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class,SessionTimeIT.Time.class})
@Tag("identity-safety")
class SessionTimeIT {
    @TestConfiguration static class Time {
        @Bean @Primary MutableClock sessionClock() { return MutableClock.at(Instant.parse("2031-01-01T00:00:00Z")); }
    }
    @Autowired MutableClock clock;
    @Autowired SessionService sessions;
    @Autowired SessionTtlEraser eraser;
    @Autowired JdbcTemplate jdbc;
    @Test @DisplayName("BA-010-T3 first touch is unconditional later touches throttle and absolute TTL caps sliding")
    void sliding() {
        var b=sessions.bootstrap(null,null,null);var ctx=sessions.resolve(b.cookie,false);
        Instant created=clock.instant();clock.advance(Duration.ofSeconds(1));sessions.authorize(ctx,null,false);
        assertThat(seen(ctx)).isEqualTo(created.plusSeconds(1));
        clock.advance(Duration.ofSeconds(59));sessions.authorize(ctx,null,false);
        assertThat(seen(ctx)).isEqualTo(created.plusSeconds(1));
        clock.advance(Duration.ofSeconds(1));sessions.authorize(ctx,null,false);
        assertThat(seen(ctx)).isEqualTo(created.plusSeconds(61));
        for(int i=0;i<3;i++) { clock.advance(Duration.ofDays(29));sessions.authorize(ctx,null,false); }
        assertThat(expiry(ctx)).isEqualTo(created.plus(Duration.ofDays(90)));
        clock.set(created.plus(Duration.ofDays(90)));
        assertThatThrownBy(() -> sessions.resolve(b.cookie,false)).isInstanceOf(ApiException.class);
    }
    @Test @DisplayName("BA-010-T3 idle and orphan cutoff reject at equality and preserve before cutoff")
    void cutoffs() {
        var b=sessions.bootstrap(null,null,null);var ctx=sessions.resolve(b.cookie,false);
        Instant created=clock.instant();
        eraser.erase(created.plusSeconds(899));assertThat(sessions.resolve(b.cookie,false)).isNotNull();
        eraser.erase(created.plusSeconds(900));
        assertThatThrownBy(() -> sessions.resolve(b.cookie,false)).isInstanceOf(ApiException.class);
        var active=sessions.bootstrap(null,null,null);var ac=sessions.resolve(active.cookie,false);
        sessions.authorize(ac,null,false);
        clock.set(active.expiresAt.minusNanos(1000));assertThat(sessions.resolve(active.cookie,false)).isNotNull();
        clock.advance(Duration.ofNanos(1000));
        assertThatThrownBy(() -> sessions.resolve(active.cookie,false)).isInstanceOf(ApiException.class);
        eraser.erase(clock.instant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_sessions WHERE id = ?",Integer.class,ac.sessionId())).isZero();
    }
    @Test @DisplayName("BA-010-T2 CSRF expiry at equality requires a new independent token")
    void csrfExpiry() {
        var b=sessions.bootstrap(null,null,null);var ctx=sessions.resolve(b.cookie,false);
        clock.set(b.csrf.expiresAt.minusNanos(1000));sessions.authorize(ctx,b.csrf.token,true);
        clock.advance(Duration.ofNanos(1000));
        assertThatThrownBy(() -> sessions.authorize(ctx,b.csrf.token,true)).isInstanceOf(ApiException.class);
        sessions.authorize(ctx,sessions.issueCsrf(ctx).token,true);
    }
    private Instant seen(OwnerContext c) { return jdbc.queryForObject("SELECT last_seen_at FROM demo_sessions WHERE id = ?",Timestamp.class,c.sessionId()).toInstant(); }
    private Instant expiry(OwnerContext c) { return jdbc.queryForObject("SELECT expires_at FROM demo_sessions WHERE id = ?",Timestamp.class,c.sessionId()).toInstant(); }
}
