package io.nullnull.crowd.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.SeoulLiveAreaObservation;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        return payload(replaceYn, level, pplTime, areaCode, "Y", FORECAST);
    }

    private static final String FORECAST = """
            {"FCST_TIME":"2026-09-20 16:00","FCST_CONGEST_LVL":"약간 붐빔",
             "FCST_PPLTN_MIN":"40000","FCST_PPLTN_MAX":"42000"},
            {"FCST_TIME":"2026-09-20 17:00","FCST_CONGEST_LVL":"보통",
             "FCST_PPLTN_MIN":"38000","FCST_PPLTN_MAX":"40000"}""";

    private static String payload(String replaceYn, String level, String pplTime, String areaCode,
            String fcstYn, String forecast) {
        return """
                {"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."},
                 "CITYDATA":{"AREA_NM":"%s","AREA_CD":"%s",
                  "LIVE_PPLTN_STTS":[{"AREA_NM":"%s","AREA_CD":"%s",
                    "AREA_CONGEST_LVL":"%s",
                    "AREA_CONGEST_MSG":"사람이 몰려있을 수 있지만 크게 붐비지는 않아요.",
                    "AREA_PPLTN_MIN":"42000","AREA_PPLTN_MAX":"44000",
                    "REPLACE_YN":"%s","PPLTN_TIME":"%s","FCST_YN":"%s",
                    "FCST_PPLTN":[%s]}]}}
                """.formatted(AREA, areaCode, AREA, areaCode, level, replaceYn, pplTime, fcstYn, forecast);
    }

    private static String normal() {
        return payload("N", "보통", "2026-09-20 15:15", "POI009");
    }

    private SeoulCityDataValidator.Validation validate(String body) {
        return validator.validate(body.getBytes(StandardCharsets.UTF_8), AREA);
    }

    @Test
    @DisplayName("서울 실제 citydata 응답의 RESULT.CODE 성공 필드를 읽는다")
    void dottedResultCodeIsAccepted() {
        // Field names observed from the provider on 2026-09-22. Keep only our synthetic
        // population fixture, without retaining the provider's news or other content.
        String body = normal().replace("\"CODE\":", "\"RESULT.CODE\":")
                .replace("\"MESSAGE\":", "\"RESULT.MESSAGE\":");
        assertThat(validate(body).accepted()).isTrue();
        assertThat(validate(body).observation()).isEqualTo(validate(normal()).observation());
    }

    @Test
    @DisplayName("서울 성공 코드 별칭이 오류와 충돌하면 관측을 만들지 않는다")
    void conflictingResultCodesAreRefused() {
        for (String fields : List.of(
                "\"CODE\":\"INFO-000\",\"RESULT.CODE\":\"ERROR-500\"",
                "\"CODE\":\"ERROR-500\",\"RESULT.CODE\":\"INFO-000\"",
                "\"RESULT.CODE\":\"ERROR-500\"")) {
            var validation = validate(normal().replace("\"CODE\":\"INFO-000\"", fields));
            assertThat(validation.accepted()).isFalse();
            assertThat(validation.observation()).isNull();
        }
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
    @DisplayName("BA-090-T10 제공자 플래그는 아는 값일 때만 통과한다")
    void unknownProviderFlagsAreDrift() {
        // The refused shapes. Before this, each of these was read as the safe answer: a missing or
        // unknown REPLACE_YN behaved like "N" (a real observation) and an unknown FCST_YN behaved
        // like "no forecast" - so a provider adding a value would have silently changed what we
        // store, with nothing red anywhere.
        assertThat(validate(payload("UNKNOWN", "보통", "2026-09-20 15:15", "POI009")).verdict().outcome())
                .as("REPLACE_YN unknown").isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        assertThat(validate(payload("", "보통", "2026-09-20 15:15", "POI009")).verdict().outcome())
                .as("REPLACE_YN empty").isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        assertThat(validate(normal().replace("\"REPLACE_YN\":\"N\",", "")).verdict().outcome())
                .as("REPLACE_YN missing").isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        assertThat(validate(payload("N", "보통", "2026-09-20 15:15", "POI009", "MAYBE", FORECAST))
                .verdict().outcome()).as("FCST_YN unknown").isEqualTo(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        // FCST_YN=Y with nothing in the array is a contradiction, not an empty forecast.
        assertThat(validate(payload("N", "보통", "2026-09-20 15:15", "POI009", "Y", ""))
                .verdict().outcome()).as("FCST_YN Y but empty")
                .isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);

        // The accepted shapes, so the rule refuses unknown values rather than everything. N with an
        // empty array is a provider that says it has no forecast, which is a fact, not drift.
        assertThat(validate(normal()).accepted()).as("N + Y").isTrue();
        SeoulCityDataValidator.Validation noForecast =
                validate(payload("N", "보통", "2026-09-20 15:15", "POI009", "N", ""));
        assertThat(noForecast.accepted()).as("N + N").isTrue();
        assertThat(noForecast.observation().forecastPoints()).isEmpty();
    }

    /**
     * The clause has two halves and they are NOT the same statement.
     *
     * <p>"Refused" is about the verdict; "makes no observation" is about what comes back beside it.
     * The version of this case before the id was added asserted only the first, so a validator that
     * refused a drifted response AND handed back a normalized reading would have passed it - and the
     * caller that stores readings does not consult the verdict to decide whether there is one, it
     * consults {@code observation()}.
     */
    @Test
    @DisplayName("BA-090-T1 서울 응답의 schema·enum drift 는 관측을 만들지 않고 거절된다")
    void driftIsRefusedAndProducesNoObservation() {
        record Drift(String label, String body, ProviderResponseValidator.Outcome outcome) {
        }
        List<Drift> refused = List.of(
                new Drift("a fifth congestion step", payload("N", "매우 붐빔", "2026-09-20 15:15", "POI009"),
                        ProviderResponseValidator.Outcome.ENUM_DRIFT),
                new Drift("an unknown substitution flag", payload("MAYBE", "보통", "2026-09-20 15:15", "POI009"),
                        ProviderResponseValidator.Outcome.ENUM_DRIFT),
                new Drift("a time in another format", normal().replace("2026-09-20 15:15", "15:15 2026-09-20"),
                        ProviderResponseValidator.Outcome.SCHEMA_DRIFT),
                // Bytes that are not JSON at all. Measured: a body that IS valid JSON but carries no
                // RESULT envelope (say "[]") comes back PROVIDER_ERROR instead, because the first
                // thing the validator asks is whether RESULT.CODE is INFO-000 and a missing field
                // answers no. That is the validator's shape today, not this clause's question.
                new Drift("bytes that are not JSON", "<html>proxy error</html>",
                        ProviderResponseValidator.Outcome.SCHEMA_DRIFT),
                // Not drift, and here on purpose: it is the other way a response fails to be an
                // observation, and keeping it in the same case is what stops "refused" from being
                // read as "refused for schema reasons only".
                new Drift("the provider's own error envelope", normal().replace("INFO-000", "ERROR-500"),
                        ProviderResponseValidator.Outcome.PROVIDER_ERROR));

        for (Drift drift : refused) {
            SeoulCityDataValidator.Validation validation = validate(drift.body());
            assertThat(validation.verdict().outcome()).as("%s: refused with its own reason", drift.label())
                    .isEqualTo(drift.outcome());
            assertThat(validation.accepted()).as("%s: not accepted", drift.label()).isFalse();
            assertThat(validation.observation()).as("%s: and nothing to store", drift.label()).isNull();
        }

        // The area the response is about is checked against the area we asked for, and that pairing
        // is not something a payload edit can express - the drift is between the two, not in the bytes.
        SeoulCityDataValidator.Validation wrongArea =
                validator.validate(normal().getBytes(StandardCharsets.UTF_8), "명동 관광특구");
        assertThat(wrongArea.verdict().outcome()).isEqualTo(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        assertThat(wrongArea.observation()).isNull();

        // THE CONTROL. Without it every assertion above is satisfied by a validator that refuses
        // everything and returns null always, which is also a validator that never works.
        SeoulCityDataValidator.Validation accepted = validate(normal());
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.observation()).isNotNull();
    }

}
