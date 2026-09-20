package io.nullnull.crowd.infrastructure.seoul;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.provider.ProviderResponseValidator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BA-090 Seoul citydata response")
class SeoulCityDataValidatorTest {

    private static final String AREA = "광화문·덕수궁";
    private final SeoulCityDataValidator validator = new SeoulCityDataValidator();

    /**
     * Shaped from a real response: the public sample key for 광화문·덕수궁 on 2026-09-20, trimmed to the
     * fields this service consumes. The provider also returns parking, buses, bikes, weather, events
     * and news; none of it is here, because a fixture is committed forever and that is third-party
     * content whose licence nobody reviewed.
     */
    private static String payload(String replaceYn, String level, String pplTime, String areaCode) {
        return """
                {"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."},
                 "CITYDATA":{"AREA_NM":"%s","AREA_CD":"%s",
                  "LIVE_PPLTN_STTS":[{"AREA_NM":"%s","AREA_CD":"%s",
                    "AREA_CONGEST_LVL":"%s",
                    "AREA_CONGEST_MSG":"사람이 몰려있을 수 있지만 크게 붐비지는 않아요.",
                    "AREA_PPLTN_MIN":"42000","AREA_PPLTN_MAX":"44000",
                    "REPLACE_YN":"%s","PPLTN_TIME":"%s","FCST_YN":"Y",
                    "FCST_PPLTN":[
                      {"FCST_TIME":"2026-09-20 16:00","FCST_CONGEST_LVL":"약간 붐빔",
                       "FCST_PPLTN_MIN":"40000","FCST_PPLTN_MAX":"42000"},
                      {"FCST_TIME":"2026-09-20 17:00","FCST_CONGEST_LVL":"보통",
                       "FCST_PPLTN_MIN":"38000","FCST_PPLTN_MAX":"40000"}]}]}}
                """.formatted(AREA, areaCode, AREA, areaCode, level, replaceYn, pplTime);
    }

    private static String normal() {
        return payload("N", "보통", "2026-09-20 15:15", "POI009");
    }

    private SeoulCityDataValidator.Validation validate(String body) {
        return validator.validate(body.getBytes(StandardCharsets.UTF_8), AREA);
    }

    @Test
    @DisplayName("BA-090-T6 관측 시각은 제공자의 offset 없는 시각을 KST 로 읽은 것이다")
    void wallClockIsReadAsSeoulTime() {
        SeoulLiveAreaObservation observation = validate(normal()).observation();
        // "2026-09-20 15:15" carries no offset. KST is UTC+9, so 06:15Z. Reading it as UTC would put
        // the observation nine hours in the future and every staleness decision downstream with it -
        // the shape #145 already cost this repository once.
        assertThat(observation.observedAt()).isEqualTo(Instant.parse("2026-09-20T06:15:00Z"));
        assertThat(observation.areaCode()).isEqualTo("POI009");
        assertThat(observation.congestionLevel()).isEqualTo("보통");
    }

    @Test
    @DisplayName("BA-090-T7 한 응답의 현재 관측과 예보는 따로 나온다")
    void liveAndForecastAreSeparated() {
        SeoulLiveAreaObservation observation = validate(normal()).observation();
        // One call, two kinds of statement. Invariant 6 is that they never merge: the live reading has
        // an observedAt and each forecast point has a targetAt, and no forecast level is ever the
        // answer to "how busy is it now".
        assertThat(observation.congestionLevel()).isEqualTo("보통");
        assertThat(observation.forecastPoints()).hasSize(2);
        assertThat(observation.forecastPoints().get(0).targetAt())
                .isEqualTo(Instant.parse("2026-09-20T07:00:00Z"));
        assertThat(observation.forecastPoints().get(0).congestionLevel()).isEqualTo("약간 붐빔");
        assertThat(observation.forecastPoints()).noneMatch(p -> p.targetAt().equals(observation.observedAt()));
    }

    @Test
    @DisplayName("BA-090-T8 REPLACE_YN 이 Y 인 관측은 저장되지 않는다")
    void substitutedReadingsAreRefused() {
        SeoulCityDataValidator.Validation refused = validate(payload("Y", "보통", "2026-09-20 15:15", "POI009"));
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.observation()).isNull();
        // And the same payload with N is accepted, so this refuses a flag rather than everything.
        assertThat(validate(normal()).accepted()).isTrue();
    }

    @Test
    @DisplayName("BA-090-T9 예보 발표 id 는 구역·관측시각·내용 셋 모두에 달려 있다")
    void everyHashInputIsLoadBearing() {
        String base = validate(normal()).observation().forecastIssueId();
        assertThat(base).startsWith("seoul-citydata-");

        // Each input gets its own mutation. Leaving any one out still covers most cases, which is
        // exactly why each is checked: without areaCode two quiet areas with the same twelve-slot
        // forecast share an issue; without observedAt a later publication of the same numbers is
        // indistinguishable; without the content a changed forecast keeps the old identity.
        assertThat(validate(payload("N", "보통", "2026-09-20 15:15", "POI001")).observation().forecastIssueId())
                .as("areaCode").isNotEqualTo(base);
        assertThat(validate(payload("N", "보통", "2026-09-20 15:20", "POI009")).observation().forecastIssueId())
                .as("observedAt").isNotEqualTo(base);
        assertThat(validate(normal().replace("약간 붐빔", "붐빔")).observation().forecastIssueId())
                .as("forecast content").isNotEqualTo(base);
        // Same response twice is the same issue: re-collecting a publication must not invent one.
        assertThat(validate(normal()).observation().forecastIssueId()).isEqualTo(base);
    }

    @Test
    @DisplayName("BA-090 a level, a provider error or a wrong area is refused with its own outcome")
    void refusalsCarryTheirReason() {
        assertThat(validate(payload("N", "매우 붐빔", "2026-09-20 15:15", "POI009")).verdict().outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        assertThat(validate(normal().replace("INFO-000", "ERROR-500")).verdict().outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        assertThat(validator.validate(normal().getBytes(StandardCharsets.UTF_8), "명동 관광특구")
                .verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(validate(normal().replace("2026-09-20 15:15", "15:15 2026-09-20")).verdict().outcome())
                .isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
    }
}
