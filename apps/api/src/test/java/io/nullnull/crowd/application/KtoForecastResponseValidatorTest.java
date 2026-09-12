package io.nullnull.crowd.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.provider.ProviderResponseValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KtoForecastResponseValidatorTest {

    private static final Instant FETCHED_AT = Instant.parse("2032-01-01T00:00:00Z");
    private final KtoForecastResponseValidator validator = new KtoForecastResponseValidator();
    private final KtoForecastRequest request = new KtoForecastRequest(UUID.randomUUID(), "11", "110", "테스트 관광지");

    @Test
    void preservesKtoDailyTargetsAsSeoulMidnightAndKeepsPublisherTimeAbsent() {
        KtoForecastResponseValidator.Validation validation = validate(response("""
                {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320101","cnctrRate":"42.5"},
                {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320102","cnctrRate":"67"}
                """));

        assertThat(validation.accepted()).as("verdict=%s, responseCount=%s", validation.verdict(),
                validation.responseCount()).isTrue();
        assertThat(validation.hasCoverage()).isTrue();
        KtoForecastSnapshotSet set = validation.snapshotSet();
        assertThat(set.sourceRegistryVersion()).isEqualTo(2);
        assertThat(set.points()).extracting(KtoForecastSnapshotSet.ForecastPoint::targetAt)
                .containsExactly(Instant.parse("2031-12-31T15:00:00Z"), Instant.parse("2032-01-01T15:00:00Z"));
        assertThat(set.points().get(0).value()).isEqualByComparingTo("42.5");
        assertThat(set.points().get(1).value()).isEqualByComparingTo("67");
        assertThat(set.forecastIssueId()).startsWith("kto-tats-");
        assertThat(set.staleAt()).isEqualTo(FETCHED_AT.plus(Duration.ofHours(24)));
    }

    @Test
    void rejectsProviderRowsThatWouldSilentlyChangeTheCanonicalPlaceOrRange() {
        KtoForecastResponseValidator.Validation wrongPlace = validate(response("""
                {"areaCd":"11","signguCd":"11110","tAtsNm":"다른 관광지","baseYmd":"20320101","cnctrRate":"42"}
                """));
        KtoForecastResponseValidator.Validation outOfRange = validate(response("""
                {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320101","cnctrRate":"101"}
                """));
        KtoForecastResponseValidator.Validation hourlyFantasy = validate(response("""
                {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320315","cnctrRate":"42"}
                """));

        assertThat(wrongPlace.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(outOfRange.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.RANGE);
        assertThat(hourlyFantasy.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.RANGE);
        assertThat(wrongPlace.snapshotSet()).isNull();
    }

    @Test
    void acceptsAnExplicitEmptyProviderResultWithoutInventingAnUnavailablePoint() {
        KtoForecastResponseValidator.Validation validation = validate("""
                {"response":{"header":{"resultCode":"0000"},"body":{
                  "items":{"item":[]},"totalCount":0}}}
                """);

        assertThat(validation.accepted()).isTrue();
        assertThat(validation.hasCoverage()).isFalse();
        assertThat(validation.responseCount()).isZero();
    }

    private KtoForecastResponseValidator.Validation validate(String response) {
        return validator.validate(response.getBytes(java.nio.charset.StandardCharsets.UTF_8), request, 2,
                UUID.randomUUID(), FETCHED_AT, Duration.ofHours(24));
    }

    private static String response(String items) {
        int count = items.isBlank() ? 0 : (int) items.lines().filter(line -> line.contains("baseYmd")).count();
        return """
                {"response":{"header":{"resultCode":"0000"},"body":{
                  "items":{"item":[%s]},"totalCount":%d}}}
                """.formatted(items, count);
    }
}
