package io.nullnull.identity.application;

import io.nullnull.identity.domain.DemoSession;
import io.nullnull.identity.domain.Owner;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SessionStore sessions;
    private final OwnerRepository owners;
    private final SessionProperties properties;
    private final Clock clock;
    private final LockWaitLimit locks;
    private final Duration lockTimeout;

    public SessionService(SessionStore sessions, OwnerRepository owners, SessionProperties properties,
            Clock clock, LockWaitLimit locks,
            @org.springframework.beans.factory.annotation.Value("${nullnull.idempotency.lock-timeout}") Duration lockTimeout) {
        this.sessions = sessions; this.owners = owners; this.properties = properties;
        this.clock = clock; this.locks = locks; this.lockTimeout = lockTimeout;
    }
    // Bearers intentionally use classes without generated toString implementations.
    public static final class Csrf {
        public final String token;
        public final Instant expiresAt;
        Csrf(String token, Instant expiresAt) { this.token = token; this.expiresAt = expiresAt; }
    }
    public static final class Bootstrap {
        public final Owner owner;
        public final String cookie;
        public final Csrf csrf;
        public final Instant expiresAt;
        Bootstrap(Owner owner, String cookie, Csrf csrf, Instant expiresAt) {
            this.owner = owner; this.cookie = cookie; this.csrf = csrf; this.expiresAt = expiresAt;
        }
    }
    public Bootstrap bootstrap(String cookie, String locale, String timezone) {
        locks.applyToCurrentTransaction(lockTimeout);
        Instant now = clock.instant();
        Optional<DemoSession> existing = findLocked(cookie);
        if (existing.isPresent() && alive(existing.get(), now)) {
            DemoSession session = existing.get();
            Owner owner = owners.findById(session.ownerId()).orElseThrow(SessionService::unauthorized);
            return new Bootstrap(owner, null, issue(session, now), expiry(session));
        }
        String selectedLocale = locale == null ? "ko-KR" : locale;
        String selectedZone = timezone == null ? "Asia/Seoul" : timezone;
        if (!Set.of("ko-KR", "en-US").contains(selectedLocale)
                || selectedZone.length() > 100 || !ZoneId.getAvailableZoneIds().contains(selectedZone)) {
            throw new ApiException(ProblemCode.INVALID_REQUEST, "Locale or timezone is unsupported.");
        }
        Owner owner = owners.create(Owner.anonymous(UuidV7.create(clock), selectedLocale, selectedZone, now));
        String token = token();
        DemoSession session = new DemoSession(UuidV7.create(clock), owner.id(), now,
                earlier(now.plus(properties.idle), now.plus(properties.absolute)), null, null);
        sessions.insert(session, hash(token));
        return new Bootstrap(owner, token, issue(session, now), session.expiresAt());
    }
    public OwnerContext resolve(String cookie, boolean allowRevoked) {
        locks.applyToCurrentTransaction(lockTimeout);
        Instant now = clock.instant();
        // Revoked deletion replay must survive an owner soft-delete; other operations never do.
        Optional<DemoSession> candidate = validToken(cookie) ? sessions.find(hash(cookie)) : Optional.empty();
        if (allowRevoked && candidate.isPresent() && candidate.get().revokedAt() != null
                && !candidate.get().revokedAt().isAfter(now)
                && now.isBefore(candidate.get().revokedAt().plus(Duration.ofHours(24)))) {
            DemoSession s = candidate.get();
            return new OwnerContext(s.ownerId(), s.id(), true);
        }
        DemoSession session = findLocked(cookie).filter(s -> alive(s, now))
                .orElseThrow(SessionService::unauthorized);
        return new OwnerContext(session.ownerId(), session.id(), false);
    }
    /** Revalidates under owner/session locks after Origin has passed; invalid CSRF rolls back touches. */
    public void authorize(OwnerContext context, String csrf, boolean required) {
        if (context.revoked()) { return; }
        locks.applyToCurrentTransaction(lockTimeout);
        Instant now = clock.instant();
        DemoSession session = lockContext(context, now);
        if (required && (!validToken(csrf) || !sessions.useCsrf(session.id(), hash(csrf), now))) {
            throw new ApiException(ProblemCode.CSRF_INVALID, "A valid CSRF token is required.");
        }
        if (session.lastSeenAt() == null || !now.isBefore(session.lastSeenAt().plus(properties.touchInterval))) {
            sessions.touch(session.id(), now,
                    earlier(now.plus(properties.idle), session.createdAt().plus(properties.absolute)));
        }
    }
    public Csrf issueCsrf(OwnerContext context) {
        locks.applyToCurrentTransaction(lockTimeout);
        return issue(lockContext(context, clock.instant()), clock.instant());
    }
    private DemoSession lockContext(OwnerContext context, Instant now) {
        owners.lockAlive(context.ownerId()).orElseThrow(SessionService::unauthorized);
        return sessions.lock(context.sessionId()).filter(s -> s.ownerId().equals(context.ownerId()) && alive(s, now))
                .orElseThrow(SessionService::unauthorized);
    }
    private Optional<DemoSession> findLocked(String cookie) {
        if (!validToken(cookie)) { return Optional.empty(); }
        Optional<DemoSession> found = sessions.find(hash(cookie));
        if (found.isEmpty() || owners.lockAlive(found.get().ownerId()).isEmpty()) { return Optional.empty(); }
        return sessions.lock(found.get().id());
    }
    private boolean alive(DemoSession s, Instant now) {
        return s.revokedAt() == null && now.isBefore(expiry(s));
    }
    private Instant expiry(DemoSession s) {
        return earlier(s.expiresAt(), s.createdAt().plus(properties.absolute));
    }
    private Csrf issue(DemoSession session, Instant now) {
        String token = token();
        Instant expires = earlier(now.plus(properties.csrf), expiry(session));
        sessions.issue(UuidV7.create(clock), session.id(), hash(token), now, expires);
        return new Csrf(token, expires);
    }
    private static String token() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
    }
    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    public static ApiException unauthorized() {
        return new ApiException(ProblemCode.UNAUTHORIZED, "A valid session is required.");
    }
}
