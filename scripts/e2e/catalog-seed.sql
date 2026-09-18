-- Catalog rows the docker-integration E2E reads (#253). Applied by scripts/integration-test.sh to
-- the throwaway integration database only, after `api` has migrated it and before `e2e` runs.
--
-- This is NOT a migration and must never become one: the rows are synthetic, they did not come
-- from a provider call, and a migration would carry them to staging and production. The database
-- they land in lives on tmpfs and dies with the compose project.
--
-- Only catalog rows are seeded. A trip is owned by the session that created it and every E2E test
-- starts from a fresh anonymous session, so a pre-seeded trip belongs to nobody the test can be —
-- the API answers 404 for it by design (invariant 11, BA-070-T1). Tests create their own trip with
-- createTrip, naming these place ids in seedItems.
--
-- Ids are the ones packages/contracts/fixtures/trips/trip-detail-scheduled.json uses for the same
-- names, so a test written against that fixture keeps its place ids. 서울숲 has no fixture id: it
-- exists because trip-create.spec searches "서울" and searchPlaces matches on the name alone.
-- Coordinates are null except on 서울숲, because searchPlaces only returns a place that has a pin
-- (JdbcCatalogPlaceQuery.search). Its (37.5, 127.0) is a round stand-in chosen to look synthetic,
-- not an observed location. No place_external_refs row is written, so no place claims a provider's
-- attribution.

BEGIN;

INSERT INTO places (id, canonical_name, category_code, region_code, status, created_at, updated_at)
VALUES
    ('018f4b20-1a44-7e11-9c02-5d7e3f1a2b01', '경복궁', 'HS', '11', 'ACTIVE', now(), now()),
    ('018f4b20-1a44-7e11-9c02-5d7e3f1a2b03', '인사동', 'HS', '11', 'ACTIVE', now(), now());

INSERT INTO places (id, canonical_name, category_code, region_code, status, latitude, longitude,
                    created_at, updated_at)
VALUES ('018f4b20-1a44-7e11-9c02-5d7e3f1a2b04', '서울숲', 'HS', '11', 'ACTIVE', 37.5, 127.0,
        now(), now());

-- A published post needs a 1st-party cover asset (V021), on the licence V021 itself seeds.
INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url, served_url, checksum,
                          media_type, alt_text, license_checked_at)
SELECT '018f5b20-0000-7000-8000-0000000000e1', id, 'e2e-cover-1',
       'https://assets.nullnull.test/covers/e2e-cover-1.png',
       'https://assets.nullnull.test/covers/e2e-cover-1.png',
       repeat('e2', 32), 'IMAGE', '표지 일러스트', now()
FROM asset_licenses
WHERE source_code = 'NULLNULL_FIRST_PARTY';

-- Written DRAFT, linked, then published: the order V022's trigger requires.
INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id, created_at, updated_at)
VALUES ('018f5b00-0000-7000-8000-000000000001', 'DRAFT', '가을 서울 산책 코스',
        '고궁과 골목을 하루에 걷는 길',
        'https://assets.nullnull.test/covers/e2e-cover-1.png',
        '018f5b20-0000-7000-8000-0000000000e1', now(), now());

INSERT INTO post_places (post_id, place_id, position, mention_type)
VALUES ('018f5b00-0000-7000-8000-000000000001', '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01', 0, 'PRIMARY'),
       ('018f5b00-0000-7000-8000-000000000001', '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03', 1, 'SECONDARY');

UPDATE posts SET status = 'PUBLISHED', published_at = now()
WHERE id = '018f5b00-0000-7000-8000-000000000001';

COMMIT;

-- Read back by the wrapper; anything but this exact line fails the gate before E2E starts.
SELECT 'e2e_catalog_seed=places:' || (SELECT count(*) FROM places WHERE id IN
           ('018f4b20-1a44-7e11-9c02-5d7e3f1a2b01', '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03',
            '018f4b20-1a44-7e11-9c02-5d7e3f1a2b04'))
    || ',published_posts:' || (SELECT count(*) FROM posts WHERE id = '018f5b00-0000-7000-8000-000000000001'
                                AND status = 'PUBLISHED');
