package io.nullnull.testsupport;

import io.nullnull.crowd.application.SeoulCityDataValidator;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.util.List;

/**
 * One Seoul citydata response per validator rule, each refused by exactly that rule (BA-090-T23).
 *
 * <p>Every response carries {@code marker}: in the value the rule judges where the rule judges one, and in the
 * provider's message otherwise. The same cases then show that no provider text reaches the refusal log
 * (BA-091-T28) - a log line that quoted the value it refused would carry the marker.
 */
public final class SeoulCityDataResponses {

    /** A response and the verdict and rule the validator should refuse it with. */
    public record Refused(SeoulCityDataValidator.Rule rule, ProviderResponseValidator.Outcome outcome, String body) {
    }

    private static final String TIME = "2026-09-20 15:15";
    private static final String FORECAST_TIME = "2026-09-20 16:00";

    private SeoulCityDataResponses() {
    }

    /** A response the validator accepts for {@code area}, observed at 15:15 KST on 2026-09-20. */
    public static String accepted(String area) {
        return response(ok("ok"), area, "POI009", population("N", "보통", TIME, "Y", forecast("보통", FORECAST_TIME)));
    }

    public static List<Refused> refusedByEachRule(String area, String marker) {
        if (!marker.matches("[A-Za-z0-9-]{1,40}")) {
            throw new IllegalArgumentException("the marker is spliced into JSON unescaped");
        }
        String live = population("N", "보통", TIME, "Y", forecast("보통", FORECAST_TIME));
        return List.of(
                refused(SeoulCityDataValidator.Rule.JSON_UNREADABLE, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        "<html><body>" + marker + "</body></html>"),
                refused(SeoulCityDataValidator.Rule.RESULT_MISSING, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response("\"MESSAGE\":\"" + marker + "\"", area, "POI009", live)),
                refused(SeoulCityDataValidator.Rule.RESULT_CODE, ProviderResponseValidator.Outcome.PROVIDER_ERROR,
                        response("\"CODE\":\"" + marker + "\",\"MESSAGE\":\"" + marker + "\"", area, "POI009", live)),
                refused(SeoulCityDataValidator.Rule.AREA_MISSING, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), null, marker, live)),
                refused(SeoulCityDataValidator.Rule.AREA_MISMATCH, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), marker, "POI009", live)),
                refused(SeoulCityDataValidator.Rule.LIVE_EMPTY, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), area, "POI009", "[]")),
                refused(SeoulCityDataValidator.Rule.REPLACE_UNKNOWN, ProviderResponseValidator.Outcome.ENUM_DRIFT,
                        response(ok(marker), area, "POI009",
                                population(marker, "보통", TIME, "Y", forecast("보통", FORECAST_TIME)))),
                refused(SeoulCityDataValidator.Rule.LEVEL_UNKNOWN, ProviderResponseValidator.Outcome.ENUM_DRIFT,
                        response(ok(marker), area, "POI009",
                                population("N", marker, TIME, "Y", forecast("보통", FORECAST_TIME)))),
                refused(SeoulCityDataValidator.Rule.TIME_FORMAT, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), area, "POI009",
                                population("N", "보통", marker, "Y", forecast("보통", FORECAST_TIME)))),
                refused(SeoulCityDataValidator.Rule.FCST_YN_UNKNOWN, ProviderResponseValidator.Outcome.ENUM_DRIFT,
                        response(ok(marker), area, "POI009",
                                population("N", "보통", TIME, marker, forecast("보통", FORECAST_TIME)))),
                refused(SeoulCityDataValidator.Rule.FORECAST_EMPTY, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), area, "POI009", population("N", "보통", TIME, "Y", "[]"))),
                refused(SeoulCityDataValidator.Rule.FCST_LEVEL_UNKNOWN, ProviderResponseValidator.Outcome.ENUM_DRIFT,
                        response(ok(marker), area, "POI009",
                                population("N", "보통", TIME, "Y", forecast(marker, FORECAST_TIME)))),
                refused(SeoulCityDataValidator.Rule.FCST_TIME_FORMAT, ProviderResponseValidator.Outcome.SCHEMA_DRIFT,
                        response(ok(marker), area, "POI009",
                                population("N", "보통", TIME, "Y", forecast("보통", marker)))),
                refused(SeoulCityDataValidator.Rule.REPLACE_SUBSTITUTED,
                        ProviderResponseValidator.Outcome.PROVIDER_ERROR,
                        response(ok(marker), area, "POI009",
                                population("Y", "보통", TIME, "Y", forecast("보통", FORECAST_TIME)))));
    }

    private static Refused refused(SeoulCityDataValidator.Rule rule, ProviderResponseValidator.Outcome outcome,
            String body) {
        return new Refused(rule, outcome, body);
    }

    private static String ok(String message) {
        return "\"CODE\":\"INFO-000\",\"MESSAGE\":\"" + message + "\"";
    }

    private static String response(String result, String areaName, String areaCode, String population) {
        return "{\"RESULT\":{" + result + "},\"CITYDATA\":{"
                + (areaName == null ? "" : "\"AREA_NM\":\"" + areaName + "\",")
                + "\"AREA_CD\":\"" + areaCode + "\",\"LIVE_PPLTN_STTS\":" + population + "}}";
    }

    private static String population(String replaced, String level, String time, String hasForecast,
            String forecast) {
        return "[{\"REPLACE_YN\":\"" + replaced + "\",\"AREA_CONGEST_LVL\":\"" + level + "\",\"PPLTN_TIME\":\""
                + time + "\",\"FCST_YN\":\"" + hasForecast + "\",\"FCST_PPLTN\":" + forecast + "}]";
    }

    private static String forecast(String level, String time) {
        return "[{\"FCST_TIME\":\"" + time + "\",\"FCST_CONGEST_LVL\":\"" + level + "\"}]";
    }
}
