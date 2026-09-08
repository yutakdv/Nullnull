package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.CommandLockTimeoutException;
import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-002-T3: the two duration properties of the guard reach the database.
 *
 * <p>Both are deliberately set to non-default values here. A test that asserts the shipped default
 * cannot tell a wired property from a hardcoded constant: it stays green when the injected value is
 * ignored, which is exactly the mistake this class exists to catch. The clock is frozen for the same
 * reason, so {@code created_at} is compared against a known instant instead of "roughly now".
 */
@SpringBootTest(properties = {
        "nullnull.idempotency.ttl=PT3H",
        "nullnull.idempotency.lock-timeout=PT1S"})
@Import({TestcontainersConfiguration.class, IdempotencyConfigurationIT.FrozenClockConfiguration.class})
@DisplayName("BA-002 idempotency TTL and lock wait bound on real PostgreSQL")
class IdempotencyConfigurationIT {

    private static final String ROUTE = "POST /trips/{tripId}/candidates";
    private static final Instant FROZEN = Instant.parse("2026-03-04T05:06:07Z");
    /** The shipped default, which this context must not be running with. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    record Receipt(String requestId) {
    }

    /** Replaces the system clock by type; @Primary avoids overriding the application's bean. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FrozenClockConfiguration {

        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(FROZEN, ZoneOffset.UTC);
        }
    }

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    OwnerRepository owners;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Clock clock;

    @Value("${nullnull.idempotency.ttl}")
    Duration configuredTtl;

    @Test
    @DisplayName("BA-002-T3 expires_at comes from the configured TTL and created_at from the clock")
    void expiryComesFromTheConfiguredTtlAndTheInjectedClock() {
        assertThat(configuredTtl).isEqualTo(Duration.ofHours(3)).isNotEqualTo(DEFAULT_TTL);
        assertThat(clock.instant()).isEqualTo(FROZEN);
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();

        guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                () -> new CommandOutcome<>(201, new Receipt("r-1")), Function.identity());

        OffsetDateTime createdAt = timestamp("created_at", owner.id(), key);
        OffsetDateTime expiresAt = timestamp("expires_at", owner.id(), key);
        assertThat(createdAt.toInstant()).isEqualTo(FROZEN);
        assertThat(Duration.between(createdAt.toInstant(), expiresAt.toInstant()))
                .isEqualTo(configuredTtl);
        assertThat(expiresAt.toInstant()).isEqualTo(FROZEN.plus(configuredTtl));
    }

    @Test
    @DisplayName("BA-002-T3 the configured lock wait bound is set on the guarded transaction")
    void theConfiguredLockTimeoutIsAppliedToTheGuardedTransaction() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        AtomicReference<String> lockTimeout = new AtomicReference<>();

        guard.execute(owner.id(), ROUTE, idempotencyKey(), hashOf("{}"),
                () -> {
                    // Same connection as the transaction, so this is the value the locks above used.
                    lockTimeout.set(jdbc.queryForObject("SHOW lock_timeout", String.class));
                    return new CommandOutcome<>(201, new Receipt("r-1"));
                },
                Function.identity());

        // "0" would mean an unbounded wait, which is what an unset or ignored property leaves behind.
        assertThat(lockTimeout.get()).isEqualTo("1s");
    }

    @Test
    @DisplayName("BA-002-T3 a command blocked on the owner lock fails instead of waiting forever")
    void aBlockedCommandFailsWithinTheBound() throws Exception {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM owners WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, owner.id());
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                    () -> new CommandOutcome<>(201, new Receipt("r-1")), Function.identity()))
                    .isInstanceOf(CommandLockTimeoutException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // Bounded, not hanging: without SET LOCAL lock_timeout this call never returns.
            assertThat(waited).isLessThan(Duration.ofSeconds(15));
            holder.rollback();
        }

        assertThat(recordCountForOwner(owner.id())).isZero();
    }

    private static String idempotencyKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), body).sha256Hex();
    }

    private OffsetDateTime timestamp(String column, UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT " + column + " FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                OffsetDateTime.class, ownerId, ROUTE, key);
    }

    private int recordCountForOwner(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_id = ?",
                Integer.class, ownerId);
    }
}
