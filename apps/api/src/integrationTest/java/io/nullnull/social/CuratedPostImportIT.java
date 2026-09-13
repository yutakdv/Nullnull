package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.CuratedPostImporter;
import io.nullnull.social.application.CuratedPostImporter.ImportReport;
import io.nullnull.social.application.CuratedPostPlan;
import io.nullnull.social.application.CuratedPostPlan.CuratedCover;
import io.nullnull.social.application.CuratedPostPlan.CuratedPlace;
import io.nullnull.social.application.CuratedPostPlan.CuratedPost;
import io.nullnull.social.application.CuratedPostPlan.CurationException;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A-031: the operations script that makes P0's curated feed posts.
 *
 * <p>The owner settled that these are made by a script rather than by a migration or a writing
 * endpoint, so this is where the script's rules are pinned. The inputs it refuses matter more than
 * the ones it accepts: a curated post is published content, and the ways it can be wrong - a place
 * that does not exist, a cover with no licence, a second run that quietly rewrites what is live -
 * are all ways the feed ends up claiming something nobody approved.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("A-031 curated post import")
class CuratedPostImportIT {

    private static final Instant PUBLISHED = Instant.parse("2026-09-09T02:00:00Z");

    @Autowired CuratedPostImporter importer;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-032-T4 A-031 a plan publishes posts with a licensed cover and a primary place")
    void aPlanBecomesAPublishedFeed() {
        UUID primary = place("큐레이션 장소 하나");
        UUID secondary = place("큐레이션 장소 둘");
        UUID postId = UUID.randomUUID();

        ImportReport report = importer.importPlan(new CuratedPostPlan(List.of(
                post(postId, List.of(new CuratedPlace(primary, true), new CuratedPlace(secondary, false))))));

        assertThat(report.published()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM posts WHERE id = ?", String.class, postId))
                .isEqualTo("PUBLISHED");
        // The primary place is at position 0, which is what Post.primaryPlaceId() reads and therefore
        // what decides the card the feed renders.
        assertThat(jdbc.queryForList("SELECT place_id FROM post_places WHERE post_id = ?"
                + " ORDER BY position", UUID.class, postId))
                .containsExactly(primary, secondary);
        assertThat(jdbc.queryForObject("SELECT mention_type FROM post_places WHERE post_id = ?"
                + " AND position = 0", String.class, postId)).isEqualTo("PRIMARY");
        // The cover hangs off the licence V021 seeded, not off one this run invented.
        assertThat(jdbc.queryForObject("""
                SELECT license.source_code FROM posts post
                  JOIN media_assets asset ON asset.id = post.cover_asset_id
                  JOIN asset_licenses license ON license.id = asset.asset_license_id
                 WHERE post.id = ?
                """, String.class, postId)).isEqualTo("NULLNULL_FIRST_PARTY");
    }

    @Test
    @DisplayName("BA-032-T4 A-031 running the same plan again changes nothing")
    void aSecondRunIsANoOp() {
        UUID primary = place("큐레이션 반복 장소");
        UUID postId = UUID.randomUUID();
        CuratedPostPlan plan = new CuratedPostPlan(List.of(
                post(postId, List.of(new CuratedPlace(primary, true)))));

        importer.importPlan(plan);
        ImportReport second = importer.importPlan(plan);

        assertThat(second.published()).isZero();
        assertThat(second.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.outcome())
                        .isEqualTo(ImportReport.Outcome.ALREADY_PRESENT));
        // One post and one cover asset, not two of either. A re-run that added a second asset would
        // leave an unreferenced image behind on every run.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE id = ?", Integer.class, postId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE source_external_id"
                + " LIKE 'curated-%'", Integer.class)).isPositive();
    }

    @Test
    @DisplayName("BA-032-T4 A-031 a place the catalog does not have is refused, and the whole plan with it")
    void aMissingPlaceRefusesTheWholePlan() {
        UUID real = place("큐레이션 실재 장소");
        UUID good = UUID.randomUUID();
        UUID bad = UUID.randomUUID();

        assertThatThrownBy(() -> importer.importPlan(new CuratedPostPlan(List.of(
                post(good, List.of(new CuratedPlace(real, true))),
                post(bad, List.of(new CuratedPlace(UUID.randomUUID(), true)))))))
                .isInstanceOf(CurationException.class)
                .hasMessageContaining("cannot create a place");

        // The first post was valid and is still not there: one transaction for the file means an
        // operator fixes the file and runs it again, rather than working out which half landed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE id IN (?, ?)", Integer.class,
                good, bad)).isZero();
    }

    @Test
    @DisplayName("BA-032-T4 A-031 no primary place, or two, is refused before it reaches the database")
    void thePrimaryPlaceRuleIsCheckedInThePlan() {
        UUID one = place("큐레이션 주 장소");
        UUID two = place("큐레이션 부 장소");

        // V022 would refuse the publish anyway, and the feed answers 503 for the whole page if such a
        // row ever existed. Checked here so the file is rejected whole rather than by a trigger
        // partway down it.
        assertThatThrownBy(() -> post(UUID.randomUUID(), List.of(new CuratedPlace(one, false))))
                .isInstanceOf(CurationException.class)
                .hasMessageContaining("exactly one PRIMARY");
        assertThatThrownBy(() -> post(UUID.randomUUID(),
                List.of(new CuratedPlace(one, true), new CuratedPlace(two, true))))
                .isInstanceOf(CurationException.class)
                .hasMessageContaining("exactly one PRIMARY");
        assertThatThrownBy(() -> post(UUID.randomUUID(), List.of()))
                .isInstanceOf(CurationException.class)
                .hasMessageContaining("names no place");
    }

    @Test
    @DisplayName("BA-032-T4 A-031 the sample plan parses and is accepted by the same rules the script uses")
    void theSamplePlanIsValid() throws Exception {
        // The file operators copy. It is parsed by the script's own reader, so a sample that drifts
        // from the schema is caught here rather than by the first person who tries to use it.
        String raw = new String(getClass().getResourceAsStream("/curation/curated-posts.sample.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        UUID first = place("샘플 장소 하나");
        UUID second = place("샘플 장소 둘");
        java.nio.file.Path plan = java.nio.file.Files.createTempFile("curated-posts", ".json");
        java.nio.file.Files.writeString(plan,
                raw.replace("PLACE_ONE", first.toString()).replace("PLACE_TWO", second.toString()));

        CuratedPostPlan parsed = io.nullnull.social.infrastructure.curation.CuratedPostImportMain
                .read(plan);
        assertThat(parsed.posts()).hasSize(2);
        assertThatCode(() -> importer.importPlan(parsed)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posts WHERE id IN (?, ?)", Integer.class,
                parsed.posts().get(0).id(), parsed.posts().get(1).id())).isEqualTo(2);
        java.nio.file.Files.deleteIfExists(plan);
    }

    private static CuratedPost post(UUID id, List<CuratedPlace> places) {
        return new CuratedPost(id, "큐레이션 글", "본문입니다.", PUBLISHED,
                new CuratedCover("https://assets.nullnull.test/covers/" + id + ".png", "표지",
                        "a".repeat(64)),
                places);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }
}
