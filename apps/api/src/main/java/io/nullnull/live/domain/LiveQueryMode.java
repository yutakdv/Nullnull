package io.nullnull.live.domain;

/**
 * What a Live area query is willing to accept as an answer.
 *
 * <p>The three values are the contract's (`LiveAreaQuery.mode`), and they are a request-side
 * vocabulary, not a result-side one: {@link io.nullnull.crowd.domain.SourceState} says what a
 * reading IS, this says what the caller will TAKE. They never merge - a caller asking
 * {@code LIVE_ONLY} still gets each area's own state in its provenance.
 */
public enum LiveQueryMode {
    AUTO, LIVE_ONLY, REPLAY_ALLOWED
}
