package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.CanonicalCatalogStore;
import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.catalog.infrastructure.kto.KtoConcentrationForecastClient;
import io.nullnull.catalog.infrastructure.kto.KtoDemoRefresh;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceClient;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceProperties;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.StubProviderServer;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ktoDemoRefresh against the real gateways, stores and PostgreSQL, with the provider stubbed and the
 * clock moved by hand past the two clocks it exists to renew: a forecast set's PT24H and the detail
 * snapshot's P7D. Every place uses a synthetic contentId of its own, which is also how teardown finds
 * this class's rows and no one else's (AGENTS.md rule 6).
 */
@SpringBootTest(properties = {
        "nullnull.env=test",
        "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]=127.0.0.1",
        "nullnull.sources.KTO_CONCENTRATION_FORECAST.allowed-hosts[0]=127.0.0.1",
        "nullnull.provider.request-timeout=PT5S"
})
@Import(TestcontainersConfiguration.class)
@DisplayName("ktoDemoRefresh renews the demo places' KTO evidence before it lapses")
class KtoDemoRefreshIT {

    /** scripts/aws/staging_operator.py OPS_LOG_LINE, the evidence half: what an ops task may echo. */
    private static final Pattern OPS_LOG_LINE = Pattern.compile("^KTO_[A-Z_]+ [A-Za-z0-9_ =:.,()<>/+-]{0,400}$");
    private static final Instant T0 = Instant.parse("2032-01-01T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderHttpClient provider;
    @Autowired SourceRegistryQuery registry;
    @Autowired SourceRegistryStore registryStore;
    @Autowired IngestAudit audit;
    @Autowired SourceQuotaStore quotaStore;
    @Autowired KtoPlaceSnapshotStore detailSnapshots;
    @Autowired CatalogIngest ingest;
    @Autowired CanonicalCatalogStore catalog;
    @Autowired KtoForecastSnapshotStore forecastSnapshots;
    @Autowired CrowdForecastQuery forecastSets;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<String> contentIds = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private StubProviderServer detailStub;
    private StubProviderServer forecastStub;
    private MutableClock clock;
    private KtoDemoRefresh refresh;

    @BeforeEach
    void wire() {
        clock = MutableClock.at(T0);
        detailStub = new StubProviderServer();
        forecastStub = new StubProviderServer();
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey("demo-refresh-test-key");
        properties.setBaseUrl(detailStub.uri(null).toString());
        properties.setForecastBaseUrl(forecastStub.uri(null).toString());
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        CollectorRunRecorder collector = new CollectorRunRecorder(audit, new SourceQuotaGuard(quotaStore, clock));
        KtoPlaceDetailGateway details = new KtoPlaceDetailGateway(detailSnapshots, registry, registryStore, collector,
                new KtoKorServiceClient(provider, properties, "test"), clock, transactionManager);
        KtoCrowdForecastGateway forecasts = new KtoCrowdForecastGateway(forecastSnapshots, registry, registryStore,
                collector, new KtoConcentrationForecastClient(provider, properties, "test"), clock, transactionManager);
        refresh = new KtoDemoRefresh(details, detailSnapshots, ingest, catalog, forecasts, forecastSnapshots,
                forecastSets, registry, clock);
    }

    /** Only this class's rows: everything reached from its own contentIds. */
    @AfterEach
    void removeOnlyOwnRows() {
        detailStub.close();
        forecastStub.close();
        List<UUID> places = new ArrayList<>();
        List<UUID> runs = new ArrayList<>();
        for (String contentId : contentIds) {
            places.addAll(jdbc.queryForList("SELECT place_id FROM place_external_refs WHERE source_code ="
                    + " 'KTO_KOR_SERVICE_2' AND external_id = ?", UUID.class, contentId));
            runs.addAll(jdbc.queryForList("SELECT collector_run_id FROM kto_place_snapshots WHERE source_code ="
                    + " 'KTO_KOR_SERVICE_2' AND content_id = ?", UUID.class, contentId));
        }
        for (UUID place : places) {
            runs.addAll(jdbc.queryForList("SELECT DISTINCT s.collector_run_id FROM snapshot_sets s JOIN crowd_snapshots c"
                    + " ON c.snapshot_set_id = s.id WHERE c.place_id = ?", UUID.class, place));
            List<UUID> sets = jdbc.queryForList("SELECT DISTINCT snapshot_set_id FROM crowd_snapshots WHERE place_id = ?",
                    UUID.class, place);
            jdbc.update("DELETE FROM crowd_snapshots WHERE place_id = ?", place);
            for (UUID set : sets) {
                jdbc.update("DELETE FROM snapshot_sets WHERE id = ?", set);
            }
        }
        for (String contentId : contentIds) {
            jdbc.update("DELETE FROM kto_place_snapshots WHERE source_code = 'KTO_KOR_SERVICE_2' AND content_id = ?",
                    contentId);
        }
        for (UUID run : runs) {
            jdbc.update("DELETE FROM api_ingest_logs WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
        for (UUID place : places) {
            jdbc.update("DELETE FROM place_external_refs WHERE place_id = ?", place);
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", place);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
    }

    @Test
    @DisplayName("the detail mode maps a new place, leaves a just-fetched snapshot alone, and renews it on the next scheduled run")
    void theDetailClock() {
        KtoPlaceRequest place = place();
        answerDetail(place);

        KtoDemoRefresh.Report first = refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        assertThat(first.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        UUID placeId = first.outcomes().get(0).placeId();
        assertThat(placeId).isNotNull();
        assertThat(detailStub.calls()).isEqualTo(1);
        assertThat(lines.get(0)).contains("planned_calls=1 per_day=1000 planned_ratio=0.0010");

        // Twelve hours in: six and a half days left, more than the six-day window. A rerun costs nothing.
        clock.advance(Duration.ofHours(12));
        KtoDemoRefresh.Report quiet = refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        assertThat(quiet.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.CURRENT);
        assertThat(quiet.calls()).isZero();
        assertThat(detailStub.calls()).isEqualTo(1);

        // The next scheduled run, five days after the first: two days left. Renewed, onto the same place.
        clock.advance(Duration.ofDays(4).plusHours(12));
        answerDetail(place);
        KtoDemoRefresh.Report renewed = refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        assertThat(renewed.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertThat(renewed.outcomes().get(0).placeId()).isEqualTo(placeId);
        assertThat(detailStub.calls()).isEqualTo(2);
        assertThat(renewed.failed()).isFalse();
        assertAllowlisted();
    }

    @Test
    @DisplayName("the next scheduled detail run renews the snapshot even when it starts sooner after its tick than the last run fetched")
    void theNextDetailRunRenewsWhicheverRunStartedFaster() {
        KtoPlaceRequest place = place();
        answerDetail(place);
        // The last run's call went out a minute after its tick: startup, and the places ahead of this one.
        clock.advance(Duration.ofMinutes(1));
        refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        assertThat(detailStub.calls()).isEqualTo(1);

        // This run starts right on the next tick, five schedule days after the last: a minute short of
        // five days after the fetch. The snapshot would lapse two days in, three before the run after
        // this one - so this is the run that has to renew it, however fast it started (#361).
        clock.advance(Duration.ofDays(5).minusMinutes(1));
        answerDetail(place);
        KtoDemoRefresh.Report next = refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        assertThat(next.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertThat(detailStub.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("the next scheduled forecast run renews the set even when it starts sooner after its tick than the last run fetched")
    void theNextForecastRunRenewsWhicheverRunStartedFaster() {
        KtoPlaceRequest place = place();
        answerDetail(place);
        refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        clock.advance(Duration.ofMinutes(1));
        answerForecast();
        refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add);
        assertThat(forecastStub.calls()).isEqualTo(1);

        // Twelve schedule hours later, a minute short of twelve hours after the fetch: the set would lapse
        // before the run after this one, so this run renews it.
        clock.advance(Duration.ofHours(12).minusMinutes(1));
        answerForecast();
        assertThat(refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add).outcomes().get(0).status())
                .isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertThat(forecastStub.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("the forecast mode leaves a just-fetched set alone, renews a stale one, and fails once the detail mapping has lapsed")
    void theForecastClock() {
        KtoPlaceRequest place = place();
        answerDetail(place);
        refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);

        answerForecast();
        KtoDemoRefresh.Report first = refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add);
        assertThat(first.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertThat(first.outcomes().get(0).evidence()).startsWith("coverage=2 ");
        assertThat(forecastStub.calls()).isEqualTo(1);

        // An hour later the set has twenty-three hours left: a rerun of the schedule costs nothing.
        clock.advance(Duration.ofHours(1));
        assertThat(refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add).outcomes().get(0).status())
                .isEqualTo(KtoDemoRefresh.Status.CURRENT);
        assertThat(forecastStub.calls()).isEqualTo(1);

        // Past PT24H the set is stale, and an optimization would have no evidence: renewed.
        clock.advance(Duration.ofHours(24));
        answerForecast();
        assertThat(refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add).outcomes().get(0).status())
                .isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertThat(forecastStub.calls()).isEqualTo(2);

        // Past P7D with no detail run in between, the forecast has nothing to ask with - and says so.
        clock.advance(Duration.ofDays(7));
        KtoDemoRefresh.Report lapsed = refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add);
        assertThat(lapsed.outcomes().get(0).status()).isEqualTo(KtoDemoRefresh.Status.FAILED);
        assertThat(lapsed.outcomes().get(0).failureCode()).isEqualTo("NO_VERIFIED_KTO_MAPPING");
        assertThat(lapsed.failed()).isTrue();
        assertThat(forecastStub.calls()).isEqualTo(2);

        // The detail run the schedule should have made restores it.
        answerDetail(place);
        refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(place), lines::add);
        answerForecast();
        assertThat(refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(place), lines::add).outcomes().get(0).status())
                .isEqualTo(KtoDemoRefresh.Status.REFRESHED);
        assertAllowlisted();
    }

    @Test
    @DisplayName("a place that fails does not stop the ones after it, and the run then counts as failed")
    void aFailedPlaceDoesNotStopTheRest() {
        KtoPlaceRequest mapped = place();
        KtoPlaceRequest neverMapped = place();
        answerDetail(mapped);
        refresh.run(KtoDemoRefresh.Mode.DETAIL, List.of(mapped), lines::add);

        answerForecast();
        KtoDemoRefresh.Report report = refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(neverMapped, mapped),
                lines::add);

        assertThat(report.outcomes()).extracting(KtoDemoRefresh.Outcome::status)
                .containsExactly(KtoDemoRefresh.Status.FAILED, KtoDemoRefresh.Status.REFRESHED);
        assertThat(report.outcomes().get(0).failureCode()).isEqualTo("NO_CANONICAL_PLACE");
        assertThat(report.failed()).isTrue();
        assertThat(lines.get(lines.size() - 1)).contains("refreshed=1 current=0 failed=1 calls=1");
        assertAllowlisted();
    }

    @Test
    @DisplayName("an empty list is refused before anything is asked")
    void anEmptyListIsRefused() {
        assertThatThrownBy(() -> refresh.run(KtoDemoRefresh.Mode.FORECAST, List.of(), lines::add))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("the place list is empty");
        assertThat(lines).isEmpty();
        assertThat(detailStub.calls() + forecastStub.calls()).isZero();
    }

    /** Every line printed passes the operator log allowlist, or CloudWatch is the only place it survives. */
    private void assertAllowlisted() {
        assertThat(lines).isNotEmpty().allSatisfy(line -> assertThat(OPS_LOG_LINE.matcher(line).matches())
                .as("%d characters: %s", line.length(), line).isTrue());
    }

    /** A synthetic contentId no other class uses, registered for teardown. */
    private KtoPlaceRequest place() {
        String contentId = "9" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        contentIds.add(contentId);
        return new KtoPlaceRequest(contentId, "12");
    }

    private void answerDetail(KtoPlaceRequest place) {
        detailStub.enqueue(new StubProviderServer.Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":{"contentid":"%s","contenttypeid":"12","title":"데모 갱신 장소",
                  "cat1":"","areacode":"","sigungucode":"","lclsSystm1":"HS","lDongRegnCd":"11",
                  "lDongSignguCd":"110","addr1":"서울특별시 종로구",
                  "mapy":"37.566535","mapx":"126.978001"}},
                  "numOfRows":1,"pageNo":1,"totalCount":1}}}
                """.formatted(place.contentId()), Duration.ZERO, java.util.Map.of()));
    }

    /** Two days ahead of the clock as it is now, which is what the provider forecasts. */
    private void answerForecast() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        DateTimeFormatter ymd = DateTimeFormatter.BASIC_ISO_DATE;
        forecastStub.enqueue(new StubProviderServer.Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":[
                    {"areaCd":"11","signguCd":"11110","tAtsNm":"데모 갱신 장소","baseYmd":"%s","cnctrRate":"42.5"},
                    {"areaCd":"11","signguCd":"11110","tAtsNm":"데모 갱신 장소","baseYmd":"%s","cnctrRate":"58"}
                  ]},"totalCount":2}}}
                """.formatted(today.plusDays(1).format(ymd), today.plusDays(2).format(ymd)), Duration.ZERO,
                java.util.Map.of()));
    }
}
