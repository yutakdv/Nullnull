package io.nullnull.social.domain;

/**
 * What a reader did with a feed card, and which of those P0 actually records.
 *
 * <p>All five are in the contract's {@code FeedFeedbackRequest.action} enum and only two are
 * accepted. That is not an oversight: #163 settled that P0 records exposure and opening, while
 * {@code HIDE}, {@code LIKE} and {@code DISLIKE} are refused because PM-011 has not settled what they
 * would mean when read back - there is no current-reaction projection, no way to undo a LIKE, and no
 * entry point to recover a hidden post. Storing them would create a state the product cannot show,
 * cannot reverse and cannot explain.
 *
 * <p>They stay in the vocabulary rather than being dropped from it. Removing a value a client may
 * send is a breaking contract change, and the day PM-011 is answered the implementation should be
 * the only thing that has to move.
 */
public enum FeedFeedbackAction {

    /** The card was shown. Deduplicated per minute, because a scroll can show it many times. */
    IMPRESSION(true),
    /** The reader opened the post. */
    OPEN(true),
    /** P1: needs a recovery entry point before it can be recorded (PM-011). */
    HIDE(false),
    /** P1: needs a reaction projection and an undo before it can be recorded (PM-011). */
    LIKE(false),
    /** P1: same as LIKE, and must not be conflated with unsaving. */
    DISLIKE(false);

    private final boolean recordedInP0;

    FeedFeedbackAction(boolean recordedInP0) {
        this.recordedInP0 = recordedInP0;
    }

    public boolean recordedInP0() {
        return recordedInP0;
    }

    public static FeedFeedbackAction of(String value) {
        for (FeedFeedbackAction action : values()) {
            if (action.name().equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown feed feedback action: " + value);
    }
}
