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
 * One operator-approved look at KTO {@code detailIntro2} - a measurement, not an adoption (A-027).
 *
 * <p>The question it answers is narrow and it is currently a guess: are {@code usetime} and
 * {@code restdate} structured enough to derive an opening window from, or free text? That guess
 * decides whether SLOT and ITEM stay permanently UNKNOWN (#181), and one call out of a 1,000/day
 * development quota turns it into a measurement. If the answer is free text we do not parse it -
 * inventing hours from prose is exactly what invariant 9 forbids - and curated hours become the
 * next question instead.
 *
 * <p>Three things this deliberately is not:
 *
 * <ul>
 * <li><b>Not an ingest.</b> No Spring context, no datasource, no snapshot, no collector run, no
 *     audit row. It reads a response and prints its shape. {@code detailIntro2} is outside the
 *     approved scope in docs/data/SOURCE_CATALOG.md and stays outside it until a separate decision;
 *     storing anything from it here would be that decision taken quietly.
 * <li><b>Not reachable from the application.</b> The URI is built here rather than in
 *     {@link KtoKorServiceProperties} on purpose: that class is the production call surface, and an
 *     unapproved operation must not become callable from a gateway just because a probe wanted it.
 * <li><b>Not self-approving.</b> The approval flag is read from the process environment only and is
 *     absent from {@link KtoSmokeEnvironment}'s allowed names, so writing it into {@code .env.local}
 *     does nothing. Approval belongs to the shell of the person running the command, because the
 *     first line of the audit trail is who decided to call a provider.
 * </ul>
 *
 * <p>Output is a field report, never the body. Provider prose is not copied into operator evidence
 * ({@code CMP-KTO-008}); for the two fields the question is about, a length and a short prefix are
 * what separates "09:00~18:00" from a paragraph, and that is all the decision needs.
 */
public final class KtoIntroProbeMain {

    private static final String APPROVAL = "NULLNULL_KTO_INTRO_PROBE_APPROVED";
    private static final String CONTENT_ID = "NULLNULL_KTO_INTRO_PROBE_CONTENT_ID";
    private static final String CONTENT_TYPE_ID = "NULLNULL_KTO_INTRO_PROBE_CONTENT_TYPE_ID";
    private static final String OFFICIAL_BASE = "https://apis.data.go.kr/B551011/KorService2";
    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");
    /** Enough of a value to tell a time range from a sentence, short enough not to be a copy. */
    private static final int PREVIEW = 40;
    /** The two fields the decision hangs on; every other key is reported by name and shape only. */
    private static final List<String> DECIDING_FIELDS = List.of("usetime", "restdate");

    private KtoIntroProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> environment = System.getenv();
        if (!"true".equals(environment.get(APPROVAL))) {
            throw new IllegalStateException(APPROVAL + " must be true in the shell running this command;"
                    + " it is not read from .env.local");
        }
        String contentId = identifier(environment.get(CONTENT_ID), CONTENT_ID);
        String contentTypeId = identifier(environment.get(CONTENT_TYPE_ID), CONTENT_TYPE_ID);

        Map<String, String> settings = KtoSmokeEnvironment.load(environment, Path.of(".env.local"));
        KtoSmokeEnvironment.sources(environment, Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_INTRO_PROBE_SETTINGS " + line));
        String serviceKey = required(settings.get("KTO_SERVICE_KEY"), "KTO_SERVICE_KEY");
        String base = settings.getOrDefault("KTO_BASE_URL", OFFICIAL_BASE);
        if (!OFFICIAL_BASE.equals(stripTrailingSlash(base))) {
            // The probe calls the real provider or nothing. A redirected base would make the shape
            // report describe something other than KTO.
            throw new IllegalStateException("the intro probe only calls the official KorService2 base");
        }
        String mobileOs = settings.getOrDefault("KTO_MOBILE_OS", "ETC");
        String mobileApp = settings.getOrDefault("KTO_MOBILE_APP", "Nullnull");

        URI uri = URI.create(OFFICIAL_BASE + "/detailIntro2?serviceKey=" + encode(serviceKey)
                + "&MobileOS=" + encode(mobileOs) + "&MobileApp=" + encode(mobileApp)
                + "&contentId=" + encode(contentId) + "&contentTypeId=" + encode(contentTypeId)
                + "&_type=json");

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            response = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        System.out.println("KTO_INTRO_PROBE_HTTP status=" + response.statusCode()
                + " bytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
        report(response.body(), contentId, contentTypeId).forEach(System.out::println);
    }

    /** The whole report, so a test can assert what it does and does not contain. */
    static List<String> report(String body, String contentId, String contentTypeId) {
        List<String> lines = new ArrayList<>();
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(body);
        } catch (RuntimeException notJson) {
            // A provider error page is a legitimate outcome of one probe; it is reported, not dumped.
            lines.add("KTO_INTRO_PROBE_RESULT verdict=NOT_JSON");
            return lines;
        }
        JsonNode header = root.path("response").path("header");
        String resultCode = header.path("resultCode").asString("");
        lines.add("KTO_INTRO_PROBE_ENVELOPE resultCode=" + resultCode
                + " totalCount=" + root.path("response").path("body").path("totalCount").asString("")
                + " contentId=" + contentId + " contentTypeId=" + contentTypeId);
        if (!"0000".equals(resultCode)) {
            lines.add("KTO_INTRO_PROBE_RESULT verdict=PROVIDER_ERROR");
            return lines;
        }
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            lines.add("KTO_INTRO_PROBE_SHAPE item=array size=" + item.size());
            item = item.isEmpty() ? item : item.get(0);
        } else {
            lines.add("KTO_INTRO_PROBE_SHAPE item=object");
        }
        if (!item.isObject()) {
            lines.add("KTO_INTRO_PROBE_RESULT verdict=NO_ITEM");
            return lines;
        }
        for (Map.Entry<String, JsonNode> field : item.properties()) {
            lines.add("KTO_INTRO_PROBE_FIELD " + describe(field.getKey(), field.getValue()));
        }
        lines.add("KTO_INTRO_PROBE_RESULT verdict=OBSERVED fields=" + item.size());
        return lines;
    }

    /**
     * One field's shape. Names and measurements always; a short prefix only for the two fields the
     * decision is about, because "is this a time range or a sentence" cannot be answered from a
     * length alone - 12 characters is both {@code 09:00~18:00} and {@code 문의要相談}.
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
            line.append(" markup=").append(text.contains("<") || text.contains("&"))
                    .append(" lines=").append(text.split("\n", -1).length)
                    .append(" preview=").append(preview(text));
        }
        return line.toString();
    }

    /** Collapses whitespace so a multi-line value cannot break the one-line-per-field report. */
    static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW ? flat : flat.substring(0, PREVIEW) + "…";
    }

    private static String identifier(String value, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalStateException(name + " must be a positive provider identifier");
        }
        return trimmed;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not configured");
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
