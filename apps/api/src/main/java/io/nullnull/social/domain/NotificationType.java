package io.nullnull.social.domain;

/**
 * The six notification kinds, BA-085.
 *
 * <p>This is the third place the vocabulary is declared: the contract's {@code Notification.type}
 * enum publishes it, {@code V037}'s {@code notifications_type_check} stores it, and this enum is
 * what the two meet through. {@code NotificationTypeVocabularyIT} compares the three as sets in both
 * directions, for the reason {@code ProviderOutcomeVocabularyIT} records - a value added to one and
 * not the others compiles, passes every suite that does not produce that exact value, and throws
 * only on the branch that does.
 *
 * <p>docs/architecture/ERD.md §"NotificationType" lists the same six.
 */
public enum NotificationType {
    OPTIMIZATION_READY,
    OPTIMIZATION_FAILED,
    CROWD_ALERT,
    TRIP_REMINDER,
    TRIP_CONFLICT,
    SOURCE_DEGRADED;

    /**
     * The stored value as a type, refusing anything else.
     *
     * <p>A row can only hold one of the six - the CHECK sees to that - so a failure here means the
     * database and this enum have drifted apart, which is a defect and not a bad request.
     */
    public static NotificationType of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("notification type must not be null");
        }
        return NotificationType.valueOf(value);
    }
}
