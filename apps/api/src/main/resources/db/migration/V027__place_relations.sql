-- BA-024 storage half: what a related place is allowed to be, before anything can serve one.
--
-- The response path is not here and cannot be yet. RelatedPlace.place is a required PlaceSummary,
-- every summary passes CatalogPlaceProjectionService.embeddedSummaries, and that method's first line
-- is requirePublicProjection() - so a result carrying candidates waits on BA-021-T3's staging
-- evidence. What does not wait is the vocabulary, which SOURCE_CATALOG §4 already fixed: a candidate
-- our own rules derived is SIMILAR with a stated reason, EXACT needs an official direct relation
-- plus a confirmed canonical mapping, and the provider's aggregation window is preserved instead of
-- assumed. Those are storage truths, so they are stored.
--
-- The readers arrive by name: the listRelatedPlaces projection once the catalog gate opens, and the
-- apps/ai related/rank gateway (BA-024 step 3, recommendation module) that orders candidates. This
-- table holds evidence; neither ranking nor crowd comparison happens here - a relation says two
-- places are related, never that one is quieter or reachable (invariant 8).
--
-- Note for whoever writes V028: FlywayMigrationIT's upgrade case asserts every table in the previous
-- schema holds a row, so populateEveryTable will need one place_relations row.

CREATE TABLE place_relations (
    id uuid PRIMARY KEY,
    source_place_id uuid NOT NULL REFERENCES places(id),
    target_place_id uuid NOT NULL REFERENCES places(id),
    relation_type varchar(10) NOT NULL,
    derivation varchar(20) NOT NULL,
    mapping_certainty varchar(20) NOT NULL,
    relation_reason varchar(200) NOT NULL,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    effective_at timestamptz NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT place_relations_type_check CHECK (relation_type IN ('EXACT', 'SIMILAR')),
    CONSTRAINT place_relations_derivation_check CHECK (derivation IN ('PROVIDER_DIRECT', 'INTERNAL_RULE')),
    CONSTRAINT place_relations_certainty_check CHECK (mapping_certainty IN ('CONFIRMED', 'UNCERTAIN')),
    -- The reason is shown next to the candidate, so a row with nothing to say cannot exist: a
    -- candidate offered without a reason is an unexplained claim about two real places.
    CONSTRAINT place_relations_reason_check CHECK (btrim(relation_reason) <> ''),
    -- SOURCE_CATALOG §4, in the column that can enforce it. Nullnull's EXACT means an official
    -- direct relation whose canonical mapping was confirmed; anything we derived ourselves is
    -- SIMILAR however sure the rule was, and an unconfirmed mapping is SIMILAR however official the
    -- provider was. The two halves are separate fields so neither can stand in for the other.
    CONSTRAINT place_relations_exact_check CHECK
        (relation_type <> 'EXACT'
         OR (derivation = 'PROVIDER_DIRECT' AND mapping_certainty = 'CONFIRMED')),
    -- A row we derived must not credit a provider, and a provider relation must not be filed as our
    -- rule: either way the evidence would name someone who did not make that claim. NULLNULL_CATALOG_RULE
    -- is the registry row for "our own taxonomy/region rule", so it is exactly the internal case -
    -- pinned here the way V008 pins kto_place_snapshots to its one source.
    CONSTRAINT place_relations_derivation_source_check CHECK
        ((derivation = 'INTERNAL_RULE') = (source_code = 'NULLNULL_CATALOG_RULE')),
    CONSTRAINT place_relations_self_check CHECK (source_place_id <> target_place_id),
    -- Open-ended is allowed - a similarity our rules derived does not expire by itself - but a window
    -- that was already empty when it was written is not evidence of anything.
    CONSTRAINT place_relations_window_check CHECK (expires_at IS NULL OR expires_at > effective_at),
    CONSTRAINT place_relations_version_check CHECK (source_registry_version > 0),
    -- One row per canonical pair per source. Two candidates that resolve to the same canonical target
    -- are the same relation, and this is where they converge: the second insert is refused rather
    -- than producing a duplicate the ranker would have to de-duplicate later.
    CONSTRAINT place_relations_pair_unique UNIQUE (source_place_id, target_place_id, source_code),
    CONSTRAINT place_relations_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version)
);

-- Both ends are canonical. catalog_require_active_place() reads NEW.place_id, which this table does
-- not have, so the same rule is expressed for the pair. Refusing a deprecated ID is what makes the
-- convergence above actually happen: the caller has to resolve to the canonical row first, and then
-- the unique constraint sees the duplicate.
CREATE FUNCTION catalog_require_active_related_places()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1 FROM places target
     WHERE target.id = NEW.source_place_id AND target.status = 'ACTIVE' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a relation must start at an active canonical place';
    END IF;
    PERFORM 1 FROM places target
     WHERE target.id = NEW.target_place_id AND target.status = 'ACTIVE' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a relation must point at an active canonical place';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_relations_active_places_guard
BEFORE INSERT OR UPDATE OF source_place_id, target_place_id ON place_relations
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_related_places();

-- V010's deprecation guard, with relations added at both ends. A merge that retired a place while
-- relations still pointed at it would leave candidates hanging off an ID the projection will not
-- resolve. Body is V025's, with the sixth EXISTS added.
CREATE OR REPLACE FUNCTION catalog_require_active_canonical_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        IF NEW.canonical_place_id IS NOT NULL THEN
            RAISE EXCEPTION 'an active place cannot have a canonical target';
        END IF;
    ELSIF NEW.status = 'DEPRECATED' THEN
        IF NEW.canonical_place_id IS NULL OR NEW.canonical_place_id = NEW.id THEN
            RAISE EXCEPTION 'a deprecated place must name a different canonical target';
        END IF;
        PERFORM 1
          FROM places target
         WHERE target.id = NEW.canonical_place_id
           AND target.status = 'ACTIVE'
         FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'a deprecated place must target an active canonical place';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown place status';
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE'
       AND (EXISTS (SELECT 1 FROM places child WHERE child.canonical_place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_localizations localization WHERE localization.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_external_refs external_ref WHERE external_ref.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_media_assets place_media WHERE place_media.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_hours_observations hours WHERE hours.place_id = OLD.id)
            OR EXISTS (SELECT 1 FROM place_relations relation
                        WHERE relation.source_place_id = OLD.id OR relation.target_place_id = OLD.id)) THEN
        RAISE EXCEPTION 'move dependent catalog records before deprecating a canonical place';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON TABLE place_relations IS
    'BA-024: evidence that two canonical places are related. EXACT is an official direct relation '
    'with a confirmed mapping; everything we derived ourselves is SIMILAR and says why. Relatedness '
    'is never a claim about crowding or reachability (invariant 8).';
