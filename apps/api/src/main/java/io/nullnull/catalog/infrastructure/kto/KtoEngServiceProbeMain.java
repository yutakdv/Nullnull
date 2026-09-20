package io.nullnull.catalog.infrastructure.kto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One operator-approved look at KTO {@code EngService2/detailCommon2} - a measurement, not an
 * adoption (BA-086).
 *
 * <p>The question is the first step of that card: <em>does a contentId we already hold in the
 * Korean catalog resolve in the English dataset, and does it come back in English?</em> Until that
 * is measured, "English POI coverage" is a plan with no denominator. {@code KtoIntroProbeMain} is
 * the pattern this follows, including why it exists at all: one call out of a shared development
 * quota turns a guess into a number.
 *
 * <p><strong>What this cannot answer, stated so nobody reads its silence as a negative.</strong>
 * Three of the four open EngService questions are not in a response body and this probe does not
 * pretend to reach them:
 *
 * <ul>
 * <li>{@code stale_after_seconds} - a refresh cadence lives in the published operation manual. If
 *     the document does not state one, the registry column stays NULL rather than borrowing
 *     KorService2's, which is a different dataset.
 * <li>Whether the daily quota is counted per authentication key or per activation - that is on the
 *     data.go.kr 마이페이지 activation detail. It matters because one key is shared, so English
 *     ingest would eat the quota P0 Korean ingest depends on.
 * <li>The 공공누리 type and the exact attribution wording - the portal's dataset page.
 * </ul>
 *
 * <p>Three things this deliberately is not, for the same reasons the intro probe lists: not an
 * ingest (no Spring context, no datasource, no snapshot, no collector run, no audit row), not
 * reachable from the application (the URI is built here, so an unapproved operation does not become
 * callable from a gateway because a probe wanted it), and not self-approving (the flag is read from
 * the process environment only and is absent from {@link KtoSmokeEnvironment}'s allowed names, so
 * writing it into {@code .env.local} does nothing).
 *
 * <p>Output is a field report, never the body (CMP-KTO-008). "Is this actually English" is answered
 * by the share of the value that is ASCII letters, which separates {@code Gyeongbokgung Palace}
 * from {@code 경복궁} without copying either into operator evidence.
 */
public final class KtoEngServiceProbeMain {

    private static final String APPROVAL = "NULLNULL_KTO_ENG_PROBE_APPROVED";
    private static final String CONTENT_ID = "NULLNULL_KTO_ENG_PROBE_CONTENT_ID";
    private static final String OFFICIAL_BASE = "https://apis.data.go.kr/B551011/EngService2";
    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");

    /**
     * The fields BA-086 is about: the three a localization row would be built from, plus the pair
     * that A-034 caught KorService2 disagreeing about - it returned {@code areacode} empty and put
     * the value in {@code lDongRegnCd}, so "the English response has the same shape" is a claim
     * that has already been false once on the Korean side.
     */
    private static final List<String> DECIDING_FIELDS =
            List.of("title", "addr1", "overview", "areacode", "ldongregncd");

    private KtoEngServiceProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> environment = System.getenv();
        if (!"true".equals(environment.get(APPROVAL))) {
            throw new IllegalStateException(APPROVAL + " must be true in the shell running this command;"
                    + " it is not read from .env.local");
        }
        String contentId = identifier(environment.get(CONTENT_ID), CONTENT_ID);

        Map<String, String> settings = KtoSmokeEnvironment.load(environment, Path.of(".env.local"));
        KtoSmokeEnvironment.sources(environment, Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_ENG_PROBE_SETTINGS " + line));
        String serviceKey = required(settings.get("KTO_SERVICE_KEY"), "KTO_SERVICE_KEY");
        String mobileOs = settings.getOrDefault("KTO_MOBILE_OS", "ETC");
        String mobileApp = settings.getOrDefault("KTO_MOBILE_APP", "Nullnull");

        // KTO_BASE_URL is deliberately NOT consulted: it is the Korean base, and an operator who
        // repointed it has repointed the Korean gateway, not chosen an English destination.
        URI uri = URI.create(OFFICIAL_BASE + "/detailCommon2?serviceKey=" + encode(serviceKey)
                + "&MobileOS=" + encode(mobileOs) + "&MobileApp=" + encode(mobileApp)
                + "&contentId=" + encode(contentId) + "&_type=json");

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            response = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        System.out.println("KTO_ENG_PROBE_HTTP status=" + response.statusCode()
                + " bytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
        report(response.body(), contentId).forEach(System.out::println);
    }

    /** The whole report, so a test can assert what it does and does not contain. */
    static List<String> report(String body, String contentId) {
        List<String> lines = new ArrayList<>();
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(body);
        } catch (RuntimeException notJson) {
            lines.add("KTO_ENG_PROBE_RESULT verdict=NOT_JSON");
            return lines;
        }
        String resultCode = root.path("response").path("header").path("resultCode").asString("");
        String totalCount = root.path("response").path("body").path("totalCount").asString("");
        lines.add("KTO_ENG_PROBE_ENVELOPE resultCode=" + resultCode
                + " totalCount=" + totalCount + " contentId=" + contentId);
        if (!"0000".equals(resultCode)) {
            lines.add("KTO_ENG_PROBE_RESULT verdict=PROVIDER_ERROR");
            return lines;
        }
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            lines.add("KTO_ENG_PROBE_SHAPE item=array size=" + item.size());
            item = item.isEmpty() ? item : item.get(0);
        } else {
            lines.add("KTO_ENG_PROBE_SHAPE item=object");
        }
        if (!item.isObject()) {
            // totalCount 0 with a well-formed envelope IS the answer to the linkage question for
            // this id: the Korean content id is not carried by the English dataset.
            lines.add("KTO_ENG_PROBE_RESULT verdict=NO_ITEM");
            return lines;
        }
        for (Map.Entry<String, JsonNode> field : item.properties()) {
            lines.add("KTO_ENG_PROBE_FIELD " + describe(field.getKey(), field.getValue()));
        }
        lines.add("KTO_ENG_PROBE_RESULT verdict=OBSERVED fields=" + item.size());
        return lines;
    }

    /**
     * One field's shape. Names and measurements always; for the deciding fields also the ASCII
     * letter share, which is what tells an English value from a Korean one without quoting either.
     */
    static String describe(String name, JsonNode value) {
        String kind = value.isObject() ? "object" : value.isArray() ? "array"
                : value.isNull() ? "null" : "scalar";
        StringBuilder line = new StringBuilder("name=").append(name).append(" type=").append(kind);
        if (!"scalar".equals(kind)) {
            return line.toString();
        }
        String text = value.asString("");
        line.append(" length=").append(text.length()).append(" empty=").append(text.isBlank());
        if (DECIDING_FIELDS.contains(name.toLowerCase(Locale.ROOT)) && !text.isBlank()) {
            line.append(" asciiLetterPercent=").append(asciiLetterPercent(text))
                    .append(" hangul=").append(containsHangul(text))
                    .append(" markup=").append(text.contains("<") || text.contains("&"));
        }
        return line.toString();
    }

    /**
     * Share of the value's letters that are ASCII, as a whole percent. Letters only: punctuation,
     * digits and spaces are the same in both languages, so counting them would drag every value
     * towards the middle and blur exactly the distinction being measured.
     */
    static int asciiLetterPercent(String text) {
        long letters = text.codePoints().filter(Character::isLetter).count();
        if (letters == 0) {
            return 0;
        }
        long ascii = text.codePoints().filter(Character::isLetter).filter(point -> point < 128).count();
        return (int) Math.round(100.0 * ascii / letters);
    }

    /** Hangul syllables and jamo, so "the English field echoed the Korean value" is one flag. */
    static boolean containsHangul(String text) {
        return text.codePoints().anyMatch(point ->
                (point >= 0xAC00 && point <= 0xD7A3)
                || (point >= 0x1100 && point <= 0x11FF)
                || (point >= 0x3130 && point <= 0x318F));
    }

    private static String identifier(String value, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalStateException(name + " must be a positive provider identifier");
        }
        return trimmed;
    }

    private static String required(String value, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException(name + " is required");
        }
        return trimmed;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
