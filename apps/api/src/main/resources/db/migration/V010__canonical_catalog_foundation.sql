-- BA-022 C3 foundation: canonical catalog data only. Public /places projection remains deliberately
-- unwired until BA-021-T3 establishes the staging actual-call -> public-provenance chain.
--
-- A deprecated place is an old internal ID that resolves to one ACTIVE canonical place. Content,
-- external references and media are moved to the active row before it is deprecated, so a read
-- projection can never accidentally render stale duplicate data under the old ID.
CREATE TABLE places (
    id uuid PRIMARY KEY,
    canonical_place_id uuid REFERENCES places(id),
    canonical_name varchar(200) NOT NULL,
    category_code varchar(100) NOT NULL,
    latitude numeric(9, 6),
    longitude numeric(9, 6),
    region_code varchar(100) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT places_name_check CHECK (btrim(canonical_name) <> ''),
    CONSTRAINT places_category_check CHECK (btrim(category_code) <> ''),
    CONSTRAINT places_region_check CHECK (btrim(region_code) <> ''),
    CONSTRAINT places_coordinates_check CHECK
        ((latitude IS NULL AND longitude IS NULL)
         OR (latitude IS NOT NULL AND longitude IS NOT NULL
             AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)),
    CONSTRAINT places_status_check CHECK (status IN ('ACTIVE', 'DEPRECATED')),
    CONSTRAINT places_canonical_shape_check CHECK
        ((status = 'ACTIVE' AND canonical_place_id IS NULL)
         OR (status = 'DEPRECATED' AND canonical_place_id IS NOT NULL AND canonical_place_id <> id)),
    CONSTRAINT places_timestamps_check CHECK (updated_at >= created_at)
);
CREATE INDEX places_canonical_place_idx ON places(canonical_place_id)
    WHERE canonical_place_id IS NOT NULL;

-- SQL CHECK constraints cannot inspect a different row. This trigger additionally requires every
-- deprecated ID to point directly at an active canonical row, which prevents chains and cycles.
CREATE FUNCTION catalog_require_active_canonical_target()
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
            OR EXISTS (SELECT 1 FROM place_media_assets place_media WHERE place_media.place_id = OLD.id)) THEN
        RAISE EXCEPTION 'move dependent catalog records before deprecating a canonical place';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE place_localizations (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES places(id),
    locale varchar(35) NOT NULL,
    name varchar(200) NOT NULL,
    short_description varchar(10000),
    address varchar(500),
    search_tokens jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at timestamptz NOT NULL,
    CONSTRAINT place_localizations_locale_check CHECK (char_length(btrim(locale)) BETWEEN 2 AND 35),
    CONSTRAINT place_localizations_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT place_localizations_description_check CHECK
        (short_description IS NULL OR btrim(short_description) <> ''),
    CONSTRAINT place_localizations_address_check CHECK (address IS NULL OR btrim(address) <> ''),
    CONSTRAINT place_localizations_tokens_check CHECK (jsonb_typeof(search_tokens) = 'array'),
    CONSTRAINT place_localizations_place_locale_unique UNIQUE (place_id, locale)
);
CREATE INDEX place_localizations_locale_name_idx
    ON place_localizations(locale, lower(name), place_id);

CREATE TABLE place_external_refs (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES places(id),
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    external_id varchar(200) NOT NULL,
    external_type varchar(100) NOT NULL,
    verified_at timestamptz NOT NULL,
    CONSTRAINT place_external_refs_source_version_check CHECK (source_registry_version > 0),
    CONSTRAINT place_external_refs_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version),
    CONSTRAINT place_external_refs_external_id_check CHECK (btrim(external_id) <> ''),
    CONSTRAINT place_external_refs_external_type_check CHECK (btrim(external_type) <> ''),
    CONSTRAINT place_external_refs_source_external_unique UNIQUE (source_code, external_id, external_type)
);
CREATE INDEX place_external_refs_place_idx ON place_external_refs(place_id);

-- Asset rows are allowed only after a source-specific manual review has produced a reviewed
-- license record. There is intentionally no thumbnail URL on places/localizations that could bypass
-- this boundary.
CREATE TABLE asset_licenses (
    id uuid PRIMARY KEY,
    source_code varchar(64) NOT NULL REFERENCES source_registry(code),
    source_registry_version bigint NOT NULL,
    external_license_code varchar(100),
    license_name varchar(200) NOT NULL,
    license_url varchar(500),
    attribution_template varchar(500),
    redistribution_allowed boolean NOT NULL,
    derivative_allowed boolean NOT NULL,
    reviewed_at timestamptz NOT NULL,
    CONSTRAINT asset_licenses_source_version_check CHECK (source_registry_version > 0),
    CONSTRAINT asset_licenses_source_revision_fk
        FOREIGN KEY (source_code, source_registry_version)
        REFERENCES source_registry_revisions(source_code, version),
    CONSTRAINT asset_licenses_external_code_check
        CHECK (external_license_code IS NULL OR btrim(external_license_code) <> ''),
    CONSTRAINT asset_licenses_name_check CHECK (btrim(license_name) <> ''),
    CONSTRAINT asset_licenses_url_check
        CHECK (license_url IS NULL OR license_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT asset_licenses_attribution_check
        CHECK (attribution_template IS NULL OR btrim(attribution_template) <> ''),
    CONSTRAINT asset_licenses_source_external_unique
        UNIQUE (source_code, source_registry_version, external_license_code)
);

CREATE TABLE media_assets (
    id uuid PRIMARY KEY,
    asset_license_id uuid NOT NULL REFERENCES asset_licenses(id),
    source_external_id varchar(200) NOT NULL,
    origin_url varchar(2000) NOT NULL,
    served_url varchar(2000),
    checksum varchar(64) NOT NULL,
    media_type varchar(20) NOT NULL,
    alt_text varchar(500),
    license_checked_at timestamptz NOT NULL,
    expires_at timestamptz,
    CONSTRAINT media_assets_source_external_id_check CHECK (btrim(source_external_id) <> ''),
    CONSTRAINT media_assets_origin_url_check CHECK (origin_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT media_assets_served_url_check
        CHECK (served_url IS NULL OR served_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT media_assets_checksum_check CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT media_assets_type_check CHECK (media_type = 'IMAGE'),
    CONSTRAINT media_assets_alt_check CHECK (alt_text IS NULL OR btrim(alt_text) <> ''),
    CONSTRAINT media_assets_expiry_check CHECK (expires_at IS NULL OR expires_at > license_checked_at),
    CONSTRAINT media_assets_license_source_checksum_unique
        UNIQUE (asset_license_id, source_external_id, checksum)
);

CREATE TABLE place_media_assets (
    place_id uuid NOT NULL REFERENCES places(id),
    media_asset_id uuid NOT NULL REFERENCES media_assets(id),
    position integer NOT NULL,
    PRIMARY KEY (place_id, media_asset_id),
    CONSTRAINT place_media_assets_position_check CHECK (position >= 0),
    CONSTRAINT place_media_assets_place_position_unique UNIQUE (place_id, position)
);

-- Existing secondary-table records must never be attached to a deprecated ID. The same active-row
-- rule is used for localizations, provider references and media so duplicate merges are explicit.
CREATE FUNCTION catalog_require_active_place()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1 FROM places target WHERE target.id = NEW.place_id AND target.status = 'ACTIVE' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'catalog content must attach to an active canonical place';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER place_localizations_active_place_guard
BEFORE INSERT OR UPDATE OF place_id ON place_localizations
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_place();

CREATE TRIGGER place_external_refs_active_place_guard
BEFORE INSERT OR UPDATE OF place_id ON place_external_refs
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_place();

CREATE TRIGGER place_media_assets_active_place_guard
BEFORE INSERT OR UPDATE OF place_id ON place_media_assets
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_place();

-- A served URL represents redistribution. Metadata may be retained for a reviewed, origin-only
-- asset, but a license that disallows redistribution cannot be projected through a Nullnull URL.
CREATE FUNCTION catalog_require_redistributable_license()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    allowed boolean;
BEGIN
    IF NEW.served_url IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT redistribution_allowed
      INTO allowed
      FROM asset_licenses
     WHERE id = NEW.asset_license_id
     FOR SHARE;
    IF NOT FOUND OR NOT allowed THEN
        RAISE EXCEPTION 'a served media asset requires a redistributable reviewed license';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_assets_redistribution_guard
BEFORE INSERT OR UPDATE OF asset_license_id, served_url ON media_assets
FOR EACH ROW EXECUTE FUNCTION catalog_require_redistributable_license();

-- Rights withdrawal is a two-step operation: remove served URLs first (placeholder degradation),
-- then make the reviewed license non-redistributable. This prevents an update from leaving an
-- already-served asset behind with a revoked right.
CREATE FUNCTION catalog_prevent_license_downgrade()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.redistribution_allowed AND NOT NEW.redistribution_allowed
       AND EXISTS (SELECT 1 FROM media_assets asset
                    WHERE asset.asset_license_id = OLD.id AND asset.served_url IS NOT NULL) THEN
        RAISE EXCEPTION 'remove served media before revoking redistribution permission';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER asset_licenses_redistribution_downgrade_guard
BEFORE UPDATE OF redistribution_allowed ON asset_licenses
FOR EACH ROW EXECUTE FUNCTION catalog_prevent_license_downgrade();

CREATE TRIGGER places_active_canonical_target_guard
BEFORE INSERT OR UPDATE OF status, canonical_place_id ON places
FOR EACH ROW EXECUTE FUNCTION catalog_require_active_canonical_target();
