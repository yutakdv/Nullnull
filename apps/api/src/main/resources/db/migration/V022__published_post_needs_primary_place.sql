-- A published post without a primary place breaks listFeed for every reader, and nothing refused it.
--
-- FeedCard.primaryPlace is required by the contract, so FeedService cannot build a card without one
-- and answers 503 SOURCE_UNAVAILABLE - deliberately, because dropping the card silently would make
-- the page size lie (#162). One such row therefore takes the whole feed down for everyone, not just
-- for the reader who would have seen that card.
--
-- Storage allowed it: posts has no place column, post_places has no rule tying PRIMARY to PUBLISHED,
-- and FeedService's own code says so in two voices - line 71 filters null primary place ids out of
-- the hydration, and line 90 then treats that same null as a data defect a curator must fix. The
-- second reading is the right one, and this is where it becomes true.
--
-- It was found by a test fixture creating exactly that row, which is the point: the fixture was not
-- doing anything the schema forbade. The same reasoning V021 used for cover assets applies here -
-- posts has no production writer yet, so this refuses nothing that exists, and the first curated post
-- will be written against a schema that already requires a place instead of one retrofitted around
-- rows that were allowed in without one.
--
-- A CHECK cannot read another table, so this is a trigger, as V010's
-- catalog_require_active_canonical_target and V014's trip_items_require_date_in_range already are.
-- It is a CONSTRAINT TRIGGER, DEFERRABLE INITIALLY DEFERRED, because the rows must be written in an
-- order that is briefly invalid: post_places.post_id references posts, so the post exists first and
-- its place arrives second. Checking per statement would make a published post unwritable in any
-- order at all - the same reason V020 deferred the item slot constraint - so the judgement is on the
-- transaction, at COMMIT.
CREATE FUNCTION posts_require_primary_place()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_post_id uuid;
BEGIN
    -- The two tables name the post differently, and plpgsql cannot be asked for a field a record
    -- does not have: referencing NEW.post_id on posts raises before any check runs, which is how the
    -- first version of this failed on DRAFT inserts it was not meant to touch.
    IF TG_TABLE_NAME = 'posts' THEN
        v_post_id := COALESCE(NEW.id, OLD.id);
    ELSE
        v_post_id := COALESCE(NEW.post_id, OLD.post_id);
    END IF;
    IF EXISTS (SELECT 1 FROM posts WHERE id = v_post_id AND status = 'PUBLISHED')
       AND NOT EXISTS (SELECT 1 FROM post_places
                        WHERE post_id = v_post_id AND mention_type = 'PRIMARY') THEN
        RAISE EXCEPTION 'a published post must name exactly one primary place';
    END IF;
    RETURN NULL;
END;
$$;

-- Both directions. Publishing without a primary place is the obvious one; removing the primary place
-- from a post that is already published is the same broken state reached from the other side, and a
-- rule that only watched one of them would be a rule with a door next to it.
CREATE CONSTRAINT TRIGGER posts_require_primary_place_on_post
    AFTER INSERT OR UPDATE ON posts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION posts_require_primary_place();

CREATE CONSTRAINT TRIGGER posts_require_primary_place_on_places
    AFTER DELETE OR UPDATE ON post_places
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION posts_require_primary_place();
