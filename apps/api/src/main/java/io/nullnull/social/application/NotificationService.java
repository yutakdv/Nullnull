package io.nullnull.social.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.cursor.CursorSortKey;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.social.domain.NotificationDeepLink;
import io.nullnull.social.domain.NotificationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * listNotifications, markNotificationRead and markAllNotificationsRead (BA-085).
 *
 * <p><strong>The expiry rule lives here and nowhere else.</strong> ERD §6 gives a notification "90
 * days, or 30 days after it was read, whichever is earlier". Both halves are resolved to instants
 * in this class and stored; no interval arithmetic reaches SQL. That is not a style choice - it is
 * what two measurements in {@code NotificationRetentionIT} forced. {@code timestamptz + interval}
 * is STABLE, so a generated column is refused outright, and the same expression written into a
 * CHECK is accepted by PostgreSQL yet answers differently per session TimeZone: a row written under
 * UTC fails that constraint when it is re-validated under a zone that observes DST. V037 cannot be
 * edited once applied, so the rule is held in code the next person can fix.
 *
 * <p>Reading can only shorten a life, never lengthen one. {@code min(created + 90d, read + 30d)} is
 * the rule, and because an unread row already stores {@code created + 90d}, marking it read is
 * exactly {@code LEAST(expires_at, read + 30d)}.
 */
@Service
public class NotificationService {

    /** Contract: listNotifications limit, default 20, maximum 50. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final String MARK_ALL_ROUTE = "PUT /notifications/read-all";

    private final NotificationStore notifications;
    private final NotificationCapability capability;
    private final NotificationCursorProperties cursors;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;
    private final Duration readRetention;

    public NotificationService(NotificationStore notifications, NotificationCapability capability,
            NotificationCursorProperties cursors, IdempotencyGuard idempotency, ObjectMapper json,
            Clock clock,
            @Value("${nullnull.notifications.retention}") Duration retention,
            @Value("${nullnull.notifications.read-retention}") Duration readRetention) {
        this.notifications = notifications;
        this.capability = capability;
        this.cursors = cursors;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
        this.retention = requirePositive("nullnull.notifications.retention", retention);
        this.readRetention = requirePositive("nullnull.notifications.read-retention", readRetention);
    }

    /** A page of notifications plus the owner's unread count, as NotificationPage declares it. */
    public record NotificationPageView(List<NotificationStore.NotificationRow> items, String nextCursor,
            boolean hasMore, long unreadCount) {
    }

    /** What markAllNotificationsRead returns, and what the idempotency guard stores for replay. */
    public record MarkAllReadView(int updatedCount, Instant cutoffAt, long unreadCount) {
    }

    /**
     * BA-085 listNotifications: the owner's notifications, newest first.
     *
     * <p>Keyset paging over {@code created_at DESC, id DESC}, the way listTrips and
     * listOptimizationHistory page. A notification arrives at the head of that order, so an offset
     * would re-serve the row the reader just saw - the defect CursorSurfaceMatrixIT exists for.
     */
    @Transactional(readOnly = true)
    public NotificationPageView list(OwnerContext context, String cursor, Integer limit) {
        capability.require();
        int size = pageSize(limit);
        Instant now = clock.instant();
        String binding = cursors.ownerBinding(context.ownerId());
        NotificationStore.PageKey after = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims = cursors.cursorCodec().decode(cursor, now, binding,
                    NotificationCursorProperties.CONTEXT);
            if (claims.sortVersion() != NotificationCursorProperties.SORT_VERSION) {
                // A key minted under another order names a row this order would resume elsewhere.
                throw new CursorException(ProblemCode.CURSOR_INVALID);
            }
            CursorSortKey key = CursorSortKey.decode(claims.sortKey());
            after = new NotificationStore.PageKey(key.instantValue(), key.id());
        }
        // One extra row: a full page is otherwise indistinguishable from the last page, and a cursor
        // handed out for an empty next page is a wasted round trip.
        List<NotificationStore.NotificationRow> found =
                notifications.page(context.ownerId(), after, size + 1, now);
        boolean hasMore = found.size() > size;
        List<NotificationStore.NotificationRow> page = hasMore ? found.subList(0, size) : found;
        String next = hasMore ? nextCursor(page.get(page.size() - 1), binding, now) : null;
        // Counted over the whole unexpired set, not over this page: the badge is about the owner's
        // notifications, not about how far they have scrolled.
        return new NotificationPageView(page, next, hasMore,
                notifications.unreadCount(context.ownerId(), now));
    }

    /**
     * BA-085 markNotificationRead.
     *
     * <p>A notification that is not this owner's and a notification that never existed produce the
     * same answer. That is invariant 11 as {@code BA-070-T1} states it: the test of a refusal is not
     * that it refuses but that it is indistinguishable, because a 403 declines while still telling
     * the caller the id is real. The contract's {@code NotFound} response says the same thing in its
     * own description - "Resource is absent or not owned by this session".
     */
    @Transactional
    public void markRead(OwnerContext context, UUID notificationId) {
        capability.require();
        Instant now = clock.instant();
        int updated = notifications.markRead(context.ownerId(), notificationId, now,
                now.plus(readRetention));
        if (updated == 0) {
            throw new ApiException(ProblemCode.NOT_FOUND, "Notification not found.");
        }
    }

    /**
     * BA-085 markAllNotificationsRead: one mutation, not N.
     *
     * <p>Guarded rather than left to converge on its own. Converging would make a retry answer
     * {@code updatedCount: 0}, and {@code FR-NOT-02} asks for "재시도해도 결과 동일" - the same
     * result, not merely the same end state. The guard stores the whole view, so a replay returns
     * the count and cutoff the first attempt produced.
     *
     * <p>The cutoff is the server's clock, and notifications committed after it stay unread. Without
     * it a notification that arrived while the request was in flight would be marked read by a
     * request that was issued before it existed.
     */
    public MarkAllReadView markAllRead(OwnerContext context, String idempotencyKey) {
        capability.require();
        // No path parameters and no body: the request is fully described by its route and its owner.
        String fingerprint = RequestFingerprint.of("markAllNotificationsRead", Map.of(), "").sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(),
                MARK_ALL_ROUTE, idempotencyKey, fingerprint,
                () -> new IdempotencyGuard.CommandOutcome<>(200, markAllReadNow(context.ownerId())),
                value -> value);
        return json.readValue(guarded.body(), MarkAllReadView.class);
    }

    private MarkAllReadView markAllReadNow(UUID ownerId) {
        Instant cutoff = clock.instant();
        int updated = notifications.markAllRead(ownerId, cutoff, cutoff.plus(readRetention));
        return new MarkAllReadView(updated, cutoff, notifications.unreadCount(ownerId, cutoff));
    }

    /**
     * Stores a notification, resolving its expiry.
     *
     * <p><strong>No production caller yet, and that is deliberate.</strong> The slices that emit
     * notifications - an optimization finishing, a crowd alert - are not in this card, and the
     * deep link vocabulary they would write is still settling. This is the one place that knows how
     * to turn "90 days" into an instant, so it exists for the retention tests and for the producer
     * slice to call; it does not schedule, send or trigger anything by itself.
     */
    @Transactional
    public UUID record(UUID ownerId, NotificationType type, String title, String body, String deepLink) {
        Instant createdAt = clock.instant();
        return notifications.insert(UUID.randomUUID(), ownerId, type, title, body,
                NotificationDeepLink.require(deepLink), createdAt,
                createdAt.plus(retention));
    }

    /** Deletes what is past retention; {@link ExpiredNotificationEraser} is what calls it. */
    @Transactional
    public int deleteExpired(Instant now) {
        return notifications.deleteExpired(now);
    }

    private String nextCursor(NotificationStore.NotificationRow last, String binding, Instant now) {
        return cursors.cursorCodec().encode(new CursorClaims(NotificationCursorProperties.CONTEXT,
                CursorSortKey.of(last.createdAt(), last.id()).encode(), binding,
                NotificationCursorProperties.CONTEXT, NotificationCursorProperties.SORT_VERSION,
                now.plus(cursors.cursorTtl()), cursors.keyId()));
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static Duration requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            // A zero retention deletes every row on the next sweep, which reads like a policy and is
            // almost always an unset value. ExpiredAnalyticsEventEraser refuses the same way.
            throw new IllegalArgumentException(property + " must be positive, was " + value);
        }
        return value;
    }
}
