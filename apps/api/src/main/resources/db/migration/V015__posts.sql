-- BA-032: curated posts, the places they mention, and an owner's saved posts.
--
-- author_owner_id is nullable and every P0 row leaves it null: the feed is curated, and
-- user-authored posts are BA-082 (P1). The column exists because ERD §4 defines it, not because
-- anything writes it yet - a post whose author is null is "curated", not "orphaned".
CREATE TABLE posts (
    id uuid PRIMARY KEY,
    author_owner_id uuid REFERENCES owners(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL,
    title varchar(200) NOT NULL,
    body text NOT NULL,
    cover_url text NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT posts_status_check CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT posts_title_check CHECK (char_length(title) BETWEEN 1 AND 200 AND btrim(title) <> ''),
    CONSTRAINT posts_body_check CHECK (btrim(body) <> ''),
    CONSTRAINT posts_cover_check CHECK (btrim(cover_url) <> ''),
    CONSTRAINT posts_timestamps_check CHECK (updated_at >= created_at),
    -- published_at exists exactly while the post is PUBLISHED: the feed orders by it, and a
    -- published row without one would have no place in that order at all.
    CONSTRAINT posts_published_shape_check CHECK
        ((status = 'PUBLISHED' AND published_at IS NOT NULL)
         OR (status <> 'PUBLISHED' AND published_at IS NULL))
);
-- The fixed feed order, which io.nullnull.social.domain.FeedOrdering already defines as
-- `publishedAt DESC, postId ASC`. ASC on the id, not DESC: that class compares the id as its
-- canonical STRING so the Spring side and the recommendation service agree, and PostgreSQL's uuid
-- ordering is byte-wise over the same hex, so ASC here is the same order. Getting this backwards
-- would make two posts published in the same instant swap places between the two implementations.
CREATE INDEX posts_feed_idx ON posts (published_at DESC, id ASC) WHERE status = 'PUBLISHED';

-- ERD §11: unique (post_id, place_id) and (post_id, position).
CREATE TABLE post_places (
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES places(id),
    position int NOT NULL,
    mention_type varchar(20) NOT NULL,
    PRIMARY KEY (post_id, place_id),
    CONSTRAINT post_places_position_check CHECK (position >= 0),
    CONSTRAINT post_places_mention_check CHECK (mention_type IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT post_places_position_unique UNIQUE (post_id, position)
);
CREATE INDEX post_places_place_idx ON post_places (place_id);

-- ERD §11: primary key (owner_id, post_id) prevents a double save. savePost is therefore idempotent
-- at the storage layer too, not only in the service: saving twice cannot produce two rows, which is
-- what lets the second call answer 200 with the ORIGINAL savedAt instead of moving it.
CREATE TABLE saved_posts (
    owner_id uuid NOT NULL REFERENCES owners(id) ON DELETE CASCADE,
    post_id uuid NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (owner_id, post_id)
);
-- An owner's saved list, most recent first.
CREATE INDEX saved_posts_owner_idx ON saved_posts (owner_id, created_at DESC);
