package io.nullnull.social.application;

import io.nullnull.social.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The notification table, as the application needs it (BA-085).
 *
 * <p>Every method is owner-scoped. There is no "find by id" that does not also take the owner:
 * invariant 11 says the owner comes from the cookie, and a lookup that could answer about another
 * owner's row is the first half of the leak {@code BA-085-T5} refuses.
 *
 * <p><strong>No method takes an expiry rule.</strong> The caller passes instants it has already
 * resolved. Two measurements are behind that and both are recorded in
 * {@code NotificationRetentionIT}: {@code timestamptz + interval} is STABLE, so the derivation
 * cannot live in a generated column, and written into a CHECK it is accepted yet answers
 * differently per session TimeZone. Keeping interval arithmetic out of SQL entirely is what makes
 * the stored expiry the same instant wherever it is computed.
 */
public interface NotificationStore {

    /** One notification as the listing reads it. */
    record NotificationRow(UUID id, NotificationType type, String title, String body, String deepLink,
            Instant createdAt, Instant readAt) {
    }

    /** The row a page ended on, in the order this listing sorts by. */
    record PageKey(Instant createdAt, UUID id) {
    }

    /**
     * Stores one notification and returns its id.
     *
     * <p>{@code expiresAt} arrives already resolved. The caller owns the rule; this only writes it.
     */
    UUID insert(UUID id, UUID ownerId, NotificationType type, String title, String body,
            String deepLink, Instant createdAt, Instant expiresAt);

    /**
     * A page of the owner's unexpired notifications, newest first, resuming after {@code after}.
     *
     * <p>Expired rows are excluded here as well as swept. The sweep runs on a schedule, so without
     * this a traveller would be shown a notification that disappears on the next tick.
     */
    List<NotificationRow> page(UUID ownerId, PageKey after, int limit, Instant now);

    /** How many of the owner's unexpired notifications are unread. */
    long unreadCount(UUID ownerId, Instant now);

    /**
     * Marks one notification read, returning how many rows that was.
     *
     * <p>Zero means the row is not this owner's - whether because it belongs to someone else or
     * because it never existed. The caller cannot tell those apart, and {@code BA-085-T5} requires
     * that it cannot.
     *
     * <p>Marking an already-read notification read again keeps the original {@code readAt}, so the
     * expiry it set does not drift forward on every open.
     */
    int markRead(UUID ownerId, UUID notificationId, Instant now, Instant readExpiry);

    /**
     * Marks every unread notification created at or before {@code cutoff} read.
     *
     * <p>The cutoff is what keeps a notification committed while the request was in flight unread,
     * which is what the contract promises.
     */
    int markAllRead(UUID ownerId, Instant cutoff, Instant readExpiry);

    /** Deletes every notification whose expiry has passed, returning how many went. */
    int deleteExpired(Instant now);
}
