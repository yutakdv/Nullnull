-- BA-060: the structured result of parsing a pasted itinerary, and nothing of the paste itself.
--
-- There is no raw_text column, and that absence is deliberate (ERD §6: "raw text column 자체를 만들지
-- 않음"). It is not, by itself, the guarantee: structured_draft and unresolved_tokens are jsonb and
-- would accept anything the application chose to put there. What the missing column buys is that
-- storing the paste has to be a deliberate act rather than a default - nobody can persist it by
-- filling in a field that is already waiting. The part a schema cannot check is checked by
-- BA-060-T1; what that test must cover lives on the card, because this file cannot be corrected
-- once it has been applied.

CREATE TABLE itinerary_import_drafts (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES owners(id),
    status varchar(20) NOT NULL,
    -- Bumped on every remap and handed out as the ETag, so a client editing a draft it has not
    -- re-read is refused rather than silently overwriting somebody else's correction.
    version bigint NOT NULL,
    -- The parsed itinerary: items with dates, positions and canonical place ids where they resolved.
    structured_draft jsonb NOT NULL,
    -- Tokens the parser would not decide: a date with no year, an ambiguous morning/afternoon, a
    -- place name that matched nothing. They are kept apart from structured_draft because the product
    -- rule is that nobody guesses on the traveller's behalf (AGENTS.md 원칙 3) - a token here is a
    -- question for the person, and a draft holding one cannot be READY.
    unresolved_tokens jsonb NOT NULL,
    -- The trip this draft became, set once by confirm and never changed.
    --
    -- UNIQUE, and nullable so that the many drafts which never reach confirm cost nothing: PostgreSQL
    -- lets a unique column hold any number of NULLs, so this says exactly "a trip comes from at most
    -- one draft" without saying anything about drafts that were abandoned. It is also the second
    -- line under a duplicate confirm - if the application guard were removed, the row would still be
    -- refused rather than two drafts each claiming to have produced the same trip.
    --
    -- ON DELETE CASCADE is the only action the confirmed_check below permits: SET NULL would leave
    -- a CONFIRMED row with no trip, and NO ACTION would make deleting an imported trip fail on a
    -- foreign key for as long as the draft lives. Every other child of trips cascades (V013, V014,
    -- V016, V024) and deleteTrip is one statement that relies on it.
    confirmed_trip_id uuid UNIQUE REFERENCES trips(id) ON DELETE CASCADE,
    confirmed_at timestamptz,
    -- 24 hours from creation (ERD §6). The sweep reads this column; the API compares against it
    -- before every remap and confirm, because a row can be past its expiry and still present.
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT itinerary_import_drafts_status_check CHECK
        (status IN ('NEEDS_REVIEW', 'READY', 'CONFIRMED', 'EXPIRED')),
    CONSTRAINT itinerary_import_drafts_version_check CHECK (version >= 1),
    -- CONFIRMED and "has a trip" are the same fact, in both directions. Written as one biconditional
    -- rather than two independent NOT NULLs because the failure that matters is the pair coming
    -- apart: a CONFIRMED draft with no trip recorded is a trip nobody can trace back, and a draft
    -- holding a trip id while still NEEDS_REVIEW is a trip that was created without a confirmation.
    CONSTRAINT itinerary_import_drafts_confirmed_check CHECK (
        (status = 'CONFIRMED') = (confirmed_trip_id IS NOT NULL AND confirmed_at IS NOT NULL)
    ),
    CONSTRAINT itinerary_import_drafts_window_check CHECK (expires_at > created_at)
);

-- The TTL sweep's only predicate.
CREATE INDEX itinerary_import_drafts_expires_idx ON itinerary_import_drafts (expires_at);
-- Owner erasure deletes by owner, and every read is owner-scoped: a draft id alone never identifies
-- a row the caller may see (invariant 11).
CREATE INDEX itinerary_import_drafts_owner_idx ON itinerary_import_drafts (owner_id, created_at DESC);
