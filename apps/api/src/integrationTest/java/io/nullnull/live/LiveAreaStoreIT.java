package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-090 stored live areas")
class LiveAreaStoreIT {

    private static final String SOURCE = "SEOUL_CITYDATA";

    @Autowired LiveAreaStore store;
    @Autowired JdbcTemplate jdbc;

    private List<UUID> areasBefore;

    @BeforeEach
    void noteAreasAlreadyPresent() {
        areasBefore = OwnedRows.snapshot(jdbc, "live_areas");
    }

    @AfterEach
    void removeOnlyTheAreasThisTestCreated() {
        // Named rows, not "every row of this source": the gate runs every suite against ONE database,
        // so a DELETE that does not point at its own rows is a statement about every test before it.
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
    }

    @Test
    @DisplayName("BA-090 한 구역만 올리는 것은 나머지를 은퇴시키지 않는다")
    void upsertingOneAreaRetiresNothing() {
        store.replaceAreas(SOURCE, List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁"),
                new LiveAreaStore.AreaUpsert("POI010", "강남역")));

        // The per-area endpoint reports one area at a time. If that path used replaceAreas, a single
        // collection would read as "the source now publishes exactly this one area" and retire the
        // other 119 - the 2026-03 notice moved 120 of them, so the list form has to keep its meaning.
        LiveAreaStore.StoredArea again = store.upsertArea(SOURCE,
                new LiveAreaStore.AreaUpsert("POI009", "광화문광장"));

        assertThat(again.name()).as("a rename still lands on the same row").isEqualTo("광화문광장");
        assertThat(store.activeAreas(SOURCE)).extracting(LiveAreaStore.StoredArea::externalId)
                .as("the area this call said nothing about is untouched").contains("POI010");
        assertThat(jdbc.queryForObject("SELECT status FROM live_areas WHERE source_code = ?"
                + " AND external_id = ?", String.class, SOURCE, "POI010")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BA-090-T15 provider 가 이름을 바꿔도 같은 행이 유지된다")
    void aRenameKeepsTheRowItsSnapshotsPointAt() {
        List<LiveAreaStore.StoredArea> first = store.replaceAreas(SOURCE,
                List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁")));
        UUID id = first.get(0).id();

        // The 2026-03 notice renamed places. Identity is (source_code, external_id), so a rename is an
        // update of the row every snapshot already references - not a delete plus an insert, which is
        // what a name-keyed store would have done, silently orphaning the readings behind it.
        List<LiveAreaStore.StoredArea> renamed = store.replaceAreas(SOURCE,
                List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문광장")));
        assertThat(renamed).hasSize(1);
        assertThat(renamed.get(0).id()).as("same row").isEqualTo(id);
        assertThat(renamed.get(0).name()).isEqualTo("광화문광장");
    }

    @Test
    @DisplayName("BA-090-T16 목록에서 빠진 구역은 RETIRED 가 되지 삭제되지 않는다")
    void aVanishedAreaIsRetiredRatherThanDeleted() {
        List<LiveAreaStore.StoredArea> both = store.replaceAreas(SOURCE,
                List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁"),
                        new LiveAreaStore.AreaUpsert("POI014", "명동 관광특구")));
        assertThat(both).extracting(LiveAreaStore.StoredArea::externalId)
                .containsExactly("POI009", "POI014");
        UUID vanishing = both.get(1).id();
        // Another source's area, so this test also covers the NON-EMPTY retirement statement. The
        // empty-publication test covers the other one; they are two different statements and a
        // source_code clause missing from either is a sweep of everyone's rows.
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO live_areas (id, source_code, external_id, name, boundary_geojson,"
                + " status, updated_at) VALUES (?, 'DEMO_REPLAY', 'REPLAY002', '시연 구역', NULL,"
                + " 'ACTIVE', now())", other);

        List<LiveAreaStore.StoredArea> remaining = store.replaceAreas(SOURCE,
                List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁")));
        assertThat(jdbc.queryForObject("SELECT status FROM live_areas WHERE id = ?", String.class, other))
                .as("another source's area is untouched by the non-empty retirement")
                .isEqualTo("ACTIVE");

        // Gone from the active list...
        assertThat(remaining).extracting(LiveAreaStore.StoredArea::externalId).containsExactly("POI009");
        // ...but the row is still there, because readings outlive the provider's list and an area
        // that existed is not an area that never did.
        assertThat(jdbc.queryForObject("SELECT status FROM live_areas WHERE id = ?", String.class, vanishing))
                .isEqualTo("RETIRED");

        // And it comes back as the same row, not a new one.
        List<LiveAreaStore.StoredArea> back = store.replaceAreas(SOURCE,
                List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁"),
                        new LiveAreaStore.AreaUpsert("POI014", "명동 관광특구")));
        assertThat(back).extracting(LiveAreaStore.StoredArea::id).contains(vanishing);
    }

    @Test
    @DisplayName("BA-090 an empty publication retires this source's areas and touches no other source")
    void anEmptyPublicationRetiresOnlyThisSource() {
        store.replaceAreas(SOURCE, List.of(new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁")));
        // A row that belongs to someone else, planted on purpose. Without it this test is 0 == 0:
        // live_areas is empty between tests, so a retirement that swept every source would still
        // pass. Measured before this line existed - the assertion below did not fire for a statement
        // with no source_code clause at all.
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO live_areas (id, source_code, external_id, name, boundary_geojson,"
                + " status, updated_at) VALUES (?, 'DEMO_REPLAY', 'REPLAY001', '시연 구역', NULL,"
                + " 'ACTIVE', now())", other);

        assertThat(store.replaceAreas(SOURCE, List.of())).isEmpty();
        assertThat(store.activeAreas(SOURCE)).isEmpty();
        // The retirement names this source. Without that clause it would be a statement about every
        // area any other source ever stored.
        assertThat(jdbc.queryForObject("SELECT status FROM live_areas WHERE id = ?", String.class, other))
                .as("another source's area is untouched").isEqualTo("ACTIVE");
    }
}
