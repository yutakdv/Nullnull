package io.nullnull.identity.application;

import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class DeletionService {
    public static final String ROUTE = "DELETE /session";
    public static final String JOB_TYPE = "delete-owner-data";
    private static final String SCOPE_HASH = sha256("owner-data-v1");
    private final IdempotencyGuard idempotency;
    private final SessionStore sessions;
    private final OwnerRepository owners;
    private final DeletionStore deletions;
    private final JobQueue jobs;
    private final DeletionProperties properties;
    private final Clock clock;
    private final ObjectMapper json;

    private final JobProperties jobProperties;

    public DeletionService(IdempotencyGuard idempotency, SessionStore sessions,
            OwnerRepository owners, DeletionStore deletions, JobQueue jobs,
            DeletionProperties properties, JobProperties jobProperties, Clock clock, ObjectMapper json) {
        this.idempotency=idempotency; this.sessions=sessions; this.owners=owners;
        this.deletions=deletions; this.jobs=jobs; this.properties=properties;
        this.jobProperties=jobProperties; this.clock=clock; this.json=json;
    }

    public DeletionReceipt accept(OwnerContext context, String key) {
        String fingerprint = RequestFingerprint.of("deleteCurrentSession", Map.of(), "").sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = context.revoked()
                ? idempotency.replayOnly(context.ownerId(), ROUTE, key, fingerprint)
                : idempotency.execute(context.ownerId(), ROUTE, key, fingerprint,
                        () -> new CommandOutcome<>(202, create(context.ownerId())), value -> value);
        Projection projection = json.readValue(guarded.body(), Projection.class);
        String token = properties.tokens.issue(projection.requestId(), projection.statusTokenExpiresAt());
        return new DeletionReceipt(projection.requestId(), "ACCEPTED", token,
                projection.statusTokenExpiresAt(), projection.requestedAt(), projection.statusUrl());
    }

    @Transactional(readOnly = true)
    public StatusView status(UUID id, String suppliedToken) {
        DeletionRecord record = deletions.find(id).orElseThrow(DeletionService::notFound);
        if (!properties.tokens.matches(suppliedToken, id, record.tokenExpiresAt())) {
            throw notFound();
        }
        Instant now = clock.instant();
        if (!now.isBefore(record.tokenExpiresAt())) {
            throw new ApiException(ProblemCode.DELETION_STATUS_EXPIRED,
                    "The deletion status token has expired.");
        }
        if (!deletions.hasStatusTokenHash(id, properties.tokens.hash(suppliedToken))) {
            throw notFound();
        }
        var body = new DeletionStatus(id, record.status(), record.requestedAt(), record.updatedAt(),
                record.completedAt(), "PARTIAL_FAILED".equals(record.status()), record.failureCode());
        return new StatusView(body, retryAfter(record, now));
    }

    /**
     * Whole seconds a caller should wait before asking again, or null once there is nothing left to
     * wait for. This is derived from the job runtime rather than a fixed number, so changing the
     * retry configuration moves the advice with it instead of leaving the contract stale.
     */
    private Duration retryAfter(DeletionRecord record, Instant now) {
        Duration wait = switch (record.status()) {
            // Terminal: another request would return exactly what the caller already has.
            case "COMPLETED", "FAILED" -> null;
            // The server owes this one another attempt, so point at when that attempt becomes due.
            case "PARTIAL_FAILED" -> Duration.between(now,
                    jobProperties.nextAttemptAt(record.updatedAt(), Math.max(1, record.attemptCount())));
            // Accepted or running: the worker claims work at its poll interval.
            default -> jobProperties.pollInterval();
        };
        if (wait == null) {
            return null;
        }
        // Retry-After is whole seconds and 0 would invite a hot loop, so one second is the floor.
        return wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait;
    }

    /** The response body and the polling hint that travels beside it as a header, not inside it. */
    public record StatusView(DeletionStatus status, Duration retryAfter) {
    }

    private Projection create(UUID ownerId) {
        Instant now = micros(clock.instant());
        Instant expires = now.plus(properties.statusTtl);
        UUID requestId = UuidV7.create(clock);
        String statusUrl = "/api/v1/deletion-requests/" + requestId;
        String token = properties.tokens.issue(requestId, expires);
        DeletionRecord request = new DeletionRecord(requestId, ownerId, "ACCEPTED", 0,
                null, expires, now, null, null, now);
        sessions.revokeOwner(ownerId, now);
        owners.markDeleted(ownerId, now);
        deletions.create(request, properties.tokens.hash(token), UuidV7.create(clock), now,
                now.plus(properties.tombstoneRetention), SCOPE_HASH);
        jobs.enqueue(JobRequest.ready(UuidV7.create(clock), JOB_TYPE, "owner:" + ownerId,
                JobPayload.of(Map.of("ownerId", ownerId.toString(), "requestId", requestId.toString())),
                properties.retryLimit, now));
        return new Projection(requestId, expires, now, statusUrl);
    }

    private static Instant micros(Instant value) {
        return Instant.ofEpochSecond(value.getEpochSecond(), value.getNano() / 1_000 * 1_000L);
    }
    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The deletion request was not found.");
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record Projection(UUID requestId, Instant statusTokenExpiresAt,
            Instant requestedAt, String statusUrl) { }
    public record DeletionReceipt(UUID requestId, String status, String statusToken,
            Instant statusTokenExpiresAt, Instant requestedAt, String statusUrl) {
        @Override public String toString() { return "DeletionReceipt[redacted]"; }
    }
    public record DeletionStatus(UUID requestId, String status, Instant requestedAt,
            Instant updatedAt, Instant completedAt, boolean retryable, String failureCode) { }
}
