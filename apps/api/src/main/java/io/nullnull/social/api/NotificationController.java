package io.nullnull.social.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.social.application.NotificationService;
import io.nullnull.social.application.NotificationService.MarkAllReadView;
import io.nullnull.social.application.NotificationService.NotificationPageView;
import io.nullnull.social.application.NotificationStore.NotificationRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * listNotifications, markNotificationRead and markAllNotificationsRead (BA-085).
 *
 * <p>The owner comes from {@link OwnerContext}. No operation here takes an owner, a session or
 * anything else a caller could use to ask about someone else's notifications.
 */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listNotifications", security = Security.SESSION)
    public ResponseEntity<NotificationPageResponse> list(OwnerContext owner,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        NotificationPageView page = notifications.list(owner, cursor, limit);
        return ResponseEntity.ok()
                // Entirely this owner's, so nothing shared may hold a copy.
                .header("Cache-Control", "private, no-store")
                .body(NotificationPageResponse.from(page));
    }

    @PutMapping(value = "/notifications/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "markAllNotificationsRead", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<MarkAllReadResponse> readAll(OwnerContext owner,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        MarkAllReadView result = notifications.markAllRead(owner, idempotencyKey);
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(new MarkAllReadResponse(result.updatedCount(), result.cutoffAt(),
                        result.unreadCount()));
    }

    @PutMapping("/notifications/{notificationId}/read")
    @NullnullOperation(id = "markNotificationRead", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<Void> read(OwnerContext owner, @PathVariable UUID notificationId) {
        notifications.markRead(owner, notificationId);
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }

    public record NotificationPageResponse(List<NotificationResponse> items, CursorPageResponse page,
            long unreadCount) {

        static NotificationPageResponse from(NotificationPageView view) {
            return new NotificationPageResponse(
                    view.items().stream().map(NotificationResponse::from).toList(),
                    new CursorPageResponse(view.nextCursor(), view.hasMore()), view.unreadCount());
        }
    }

    public record CursorPageResponse(String nextCursor, boolean hasMore) { }

    public record NotificationResponse(UUID id, String type, String title, String body, String deepLink,
            Instant createdAt, boolean read, Instant readAt) {

        static NotificationResponse from(NotificationRow row) {
            // `read` is derived rather than stored: one timestamp cannot disagree with itself, and
            // two fields that can are how a list ends up showing a read notification as unread.
            return new NotificationResponse(row.id(), row.type().name(), row.title(), row.body(),
                    row.deepLink(), row.createdAt(), row.readAt() != null, row.readAt());
        }
    }

    public record MarkAllReadResponse(int updatedCount, Instant cutoffAt, long unreadCount) { }
}
