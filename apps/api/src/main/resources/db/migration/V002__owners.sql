-- BA-002 identity baseline: the owner aggregate every other table is scoped by
-- (docs/architecture/ERD.md §1 OWNERS, §4 "Identity", §5 OwnerKind).
-- P0 owners are anonymous: no email, name, account or precise location is stored here.
CREATE TABLE owners (
    id                   uuid         PRIMARY KEY,
    kind                 varchar(16)  NOT NULL,
    account_id           uuid,
    locale               varchar(35)  NOT NULL,
    timezone             varchar(100) NOT NULL,
    onboarding_completed boolean      NOT NULL DEFAULT false,
    -- ERD §4: null, or a non-deleted trip of the same owner; cleared when that trip is deleted.
    -- The trips table belongs to a later slice, which adds the foreign key and that rule.
    active_trip_id       uuid,
    created_at           timestamptz  NOT NULL,
    deleted_at           timestamptz,
    CONSTRAINT owners_kind_check CHECK (kind IN ('ANONYMOUS', 'ACCOUNT')),
    -- Bounds match OwnerProfile.locale/timezone in docs/api/openapi.yaml so the API can never
    -- accept a value the schema rejects; the lower bounds also reject the empty string.
    CONSTRAINT owners_locale_check CHECK (char_length(locale) BETWEEN 2 AND 35),
    CONSTRAINT owners_timezone_check CHECK (char_length(timezone) BETWEEN 1 AND 100),
    -- An anonymous owner has no account; linking one is the step that turns kind into ACCOUNT.
    CONSTRAINT owners_anonymous_account_check
        CHECK (kind <> 'ANONYMOUS' OR account_id IS NULL)
);

-- ERD §4: account_id is unique only when it is not null.
CREATE UNIQUE INDEX owners_account_id_key ON owners (account_id) WHERE account_id IS NOT NULL;
