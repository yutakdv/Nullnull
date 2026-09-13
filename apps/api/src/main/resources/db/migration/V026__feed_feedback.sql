-- BA-033: allowlisted feed interactions, and the dedup that keeps an impression storm from becoming
-- a thousand rows about one card.
--
-- The table carries no free text and no client-supplied identity: owner_id is bound from the session
-- cookie, and the only thing the request decides is which post and which action (invariant 11).

CREATE TABLE feed_feedback (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES owners(id),
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    action varchar(20) NOT NULL,
    -- What the device says happened, and when the server heard about it. Two different facts: a
    -- device clock can be wrong in either direction, which is why retention sweeps on received_at
    -- (ERD §6) and why neither column is derived from the other. There is deliberately no CHECK
    -- relating them: any bound on how far a device clock may drift would be a number nobody has
    -- measured, and it would reject real events from real phones to enforce a guess.
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    -- The minute bucket of occurred_at, as whole minutes since the epoch.
    --
    -- Written by the application rather than generated, and that is a PostgreSQL constraint rather
    -- than a preference: date_trunc('minute', timestamptz) is STABLE, not IMMUTABLE, so it can be
    -- used in neither a generated column nor an index expression nor a CHECK. An integer count of
    -- minutes is exact, is immune to the session TimeZone, and compares without a function call.
    -- FeedFeedbackIT pins that the stored bucket is the one occurred_at falls in, which is the part
    -- the database cannot check for itself.
    occurred_minute bigint NOT NULL,
    -- The vocabulary is the contract's FeedFeedbackRequest.action enum, in full. P0 accepts two of
    -- the five and refuses the rest at the boundary (#163) - the column keeps all five because the
    -- refusal is a release decision, not a schema one, and the day HIDE is implemented it must not
    -- also need a migration.
    CONSTRAINT feed_feedback_action_check CHECK
        (action IN ('IMPRESSION', 'OPEN', 'HIDE', 'LIKE', 'DISLIKE')),
    -- ERD §4: one row per owner, post, action and minute. A repeat inside the same minute converges
    -- on the row that is already there instead of adding another, so a card that scrolls past the
    -- viewport ten times in a second is one impression.
    CONSTRAINT feed_feedback_minute_unique UNIQUE (owner_id, post_id, action, occurred_minute)
);

-- ERD §8.
CREATE INDEX feed_feedback_owner_post_idx ON feed_feedback (owner_id, post_id, occurred_at DESC);
-- The retention sweep reads this one: 90 days by received_at, which is our clock and therefore the
-- one the promise is made in.
CREATE INDEX feed_feedback_received_idx ON feed_feedback (received_at);

COMMENT ON COLUMN feed_feedback.occurred_minute IS
    'BA-033: whole minutes since the epoch for occurred_at, written by the application because '
    'date_trunc is not IMMUTABLE and cannot back an index. The unique key that bounds impressions.';
