package io.nullnull.social.domain;

/**
 * What the selected trip already knows about a feed card's place.
 *
 * <p>This changes only what the card DISPLAYS. It never changes the feed's order - the feed is
 * fixed by publishedAt and id, and a per-owner state must not be able to reorder a shared listing
 * (BA-032 safety boundary). NO_TRIP_SELECTED is the honest answer when no tripId was supplied: the
 * question "is this place already in your trip" has no answer, which is not the same as "no".
 */
public enum CandidateState {
    NOT_SAVED,
    SAVED_TO_SELECTED_TRIP,
    SCHEDULED_IN_SELECTED_TRIP,
    NO_TRIP_SELECTED
}
