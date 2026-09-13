package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A-024 as the database enforces it: a post cannot be published with a cover that has no licence.
 *
 * <p>The decision - 1st-party assets only, no provider photograph - lived in a decision table and
 * nowhere else. {@code posts.cover_url} is free text, so a row whose cover was a provider photograph
 * was storable and nothing would have reported it. V021 gives the cover a foreign key to a licensed
 * asset and requires one to publish; these are the two halves of that, executed.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("A-024 post covers are 1st-party assets")
class FirstPartyCoverIT {

    /** The one asset URL this class writes, so its cleanup can name exactly its own rows. */
    private static final String COVER_URL = "https://assets.nullnull.test/covers/first-party-cover-it.png";

    @Autowired JdbcTemplate jdbc;

    /**
     * This class publishes posts that have no place, which no screen would ever create and the feed
     * cannot render: FeedService throws SOURCE_UNAVAILABLE when a card's primaryPlace is missing, and
     * that is deliberate (#162). Left behind, such a row breaks every later feed read.
     *
     * <p>It only breaks them where the database is shared. Testcontainers builds one per application
     * context, so each class with its own properties gets a fresh database locally and nothing leaks;
     * the Compose gate runs with NULLNULL_TEST_DATABASE=external and one database for the whole
     * suite, which is why this passed here and failed there. The rows are this class's to remove.
     */
    @org.junit.jupiter.api.AfterEach
    void removeOnlyTheRowsThisClassWrote() {
        jdbc.update("DELETE FROM posts WHERE cover_url LIKE 'https://x.test/%'");
        // The exact URL this class writes, not the prefix PostCovers shares with every other class.
        // The prefix form deleted assets other classes' posts still pointed at and failed on
        // posts_cover_asset_id_fkey - a cleanup removing rows it did not create, which is the same
        // mistake the catalog @AfterEach methods make and the one this file was written after.
        jdbc.update("DELETE FROM media_assets WHERE origin_url = ?", COVER_URL);
        jdbc.update("DELETE FROM places WHERE canonical_name = '표지 test 장소'");
    }

    @Test
    @DisplayName("A-024 publishing without a cover asset is refused, and with one is accepted")
    void aPublishedCoverHasToNameALicensedAsset() {
        OffsetDateTime now = OffsetDateTime.now();

        // A DRAFT may have no asset yet: the text is written before the illustration exists.
        UUID draft = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        assertThatCode(() -> jdbc.update("INSERT INTO posts (id, status, title, body, cover_url,"
                + " created_at, updated_at) VALUES (?, 'DRAFT', '초안', '본문', 'https://x.test/c.png',"
                + " ?, ?)", draft, now, now)).doesNotThrowAnyException();

        // Publishing it is where the rule applies, and the free-text URL is not enough.
        assertThatThrownBy(() -> jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ?"
                + " WHERE id = ?", now, draft))
                .hasMessageContaining("posts_published_cover_asset_check");

        // A place too, because V022 requires a published post to name one. This test is about the
        // cover, and it had been publishing posts with no place at all - a row no screen creates and
        // one that made every later feed read a 503 wherever the database is shared. The schema
        // refuses it now, which is why this line exists rather than a comment asking for care.
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '표지 test 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", draft, placeId);

        // With an asset whose licence is the 1st-party one, the same publish is accepted.
        UUID assetId = insertFirstPartyAsset(now);
        assertThatCode(() -> jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ?,"
                + " cover_asset_id = ? WHERE id = ?", now, assetId, draft)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT cover_asset_id FROM posts WHERE id = ?", UUID.class,
                draft)).isEqualTo(assetId);
    }

    @Test
    @DisplayName("A-024 the 1st-party licence requires no attribution and permits redistribution")
    void theLicenceSaysWhatTheDecisionSaid() {
        // The two flags A-024 names, read back rather than assumed. attribution_template NULL is
        // attributionRequired=false in this schema: there is nobody outside the team to credit.
        assertThat(jdbc.queryForObject("SELECT coalesce(attribution_template, '-') || ' '"
                        + " || redistribution_allowed || ' ' || derivative_allowed"
                        + " FROM asset_licenses WHERE source_code = 'NULLNULL_FIRST_PARTY'",
                String.class)).isEqualTo("- true true");

        // And the source it hangs from is internal: no external grant means nothing to review.
        assertThat(jdbc.queryForObject("SELECT license_review_state || ' ' || approval_state"
                        + " || ' ' || coalesce(license_name, '-') || ' ' || coalesce(license_url, '-')"
                        + " FROM source_registry WHERE code = 'NULLNULL_FIRST_PARTY'", String.class))
                .isEqualTo("NOT_APPLICABLE PROD_APPROVED - -");
    }

    @Test
    @DisplayName("A-024 a cover asset cannot point at a licence that does not exist")
    void thereIsNoCoverWithoutALicence() {
        // The foreign key chain is the point: cover_asset_id to media_assets to asset_licenses to
        // source_registry. An asset with an invented licence cannot be stored, so a cover can never
        // be the thing whose rights nobody checked.
        OffsetDateTime now = OffsetDateTime.now();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO media_assets (id, asset_license_id,"
                + " source_external_id, origin_url, checksum, media_type, license_checked_at)"
                + " VALUES (?, ?, 'x', 'https://x.test/a.png', ?, 'IMAGE', ?)",
                UUID.randomUUID(), UUID.randomUUID(), "0".repeat(64), now))
                .hasMessageContaining("asset_license_id");
    }

    @Test
    @DisplayName("BA-032 a published post must name a primary place, from either direction")
    void aPublishedPostCannotLoseItsPrimaryPlace() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID postId = UUID.randomUUID();
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '표지 test 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, created_at, updated_at)"
                + " VALUES (?, 'DRAFT', '장소 없는 글', '본문', 'https://x.test/p.png', ?, ?)",
                postId, now, now);
        UUID assetId = insertFirstPartyAsset(now);

        // Publishing with no place. FeedService cannot build a card without one and answers 503 for
        // the WHOLE feed, so this row would take every reader's feed down, not just its own card.
        assertThatThrownBy(() -> jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ?,"
                + " cover_asset_id = ? WHERE id = ?", now, assetId, postId))
                .hasMessageContaining("primary place");

        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", postId, placeId);
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ?, cover_asset_id = ?"
                + " WHERE id = ?", now, assetId, postId);

        // The same broken state reached from the other side: take the place away afterwards. A rule
        // that watched only the publish would have a door next to it.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM post_places WHERE post_id = ?", postId))
                .hasMessageContaining("primary place");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post_places WHERE post_id = ?",
                Integer.class, postId)).isOne();
    }

    private UUID insertFirstPartyAsset(OffsetDateTime now) {
        UUID licenceId = jdbc.queryForObject(
                "SELECT id FROM asset_licenses WHERE source_code = 'NULLNULL_FIRST_PARTY'", UUID.class);
        UUID assetId = UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,"
                + " served_url, checksum, media_type, alt_text, license_checked_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'IMAGE', '표지 일러스트', ?)",
                assetId, licenceId, "cover-" + assetId, COVER_URL, COVER_URL, "a".repeat(64), now);
        return assetId;
    }
}
