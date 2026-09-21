package io.nullnull.catalog.infrastructure.kto;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One operator-approved look at KTO {@code EngService2/locationBasedList2} around places we
 * already hold - a measurement of how English items could be matched, not a matcher (BA-086).
 *
 * <p>Why it exists: {@link KtoEngServiceProbeMain} asked whether a content id from the Korean
 * catalog resolves in the English dataset, and for 126508 the answer was {@code NO_ITEM}
 * (2026-09-21, one id only). So step 1 of the card - "canonical ID linkage quality" - is a matching
 * problem, not an id lookup. This probe measures what a match could be built from: which fields
 * an English list item carries (does any of them point back at the Korean id?), how far the nearby
 * items are, and which codes they carry.
 *
 * <p><strong>The radius is an observation radius, not a match threshold.</strong> {@value
 * #RADIUS_METERS} m decides how much of the neighbourhood this one look sees. It says nothing about
 * how close an English item must be to count as the same place - that threshold is decided after
 * this probe's distances are read, and recorded as a decision, not taken from here.
 *
 * <p>What it deliberately does not do:
 *
 * <ul>
 * <li>It does not decide a match. There is no UNIQUE/AMBIGUOUS verdict here, because that needs a
 *     distance threshold and a Korean-to-English content-type correspondence, and neither has been
 *     observed yet. It reports candidates and their measurements.
 * <li>It does not use names as evidence. Comparing a Korean title with an English one means
 *     guessing a romanisation, which is the most common shape of "linked by guess".
 * <li>It is not an ingest and not reachable from the application, for the reasons
 *     {@link KtoEngServiceProbeMain} gives. The approval flag is read from the process environment
 *     only.
 * </ul>
 *
 * <p>Output never contains provider prose (CMP-KTO-008). Identifier and code fields (content id,
 * content type, region and classification codes) are printed as values because a later reviewed
 * mapping plan needs the English content id, and a value is printed only when it has the shape of a
 * code; anything else in those fields is reported by length. Every other field - title, address,
 * overview - is reported by shape, with the title's ASCII letter share so an English value can be
 * told from a Korean echo without quoting either.
 *
 * <p><strong>Name evidence, without romanisation.</strong> English titles observed here carry the
 * Korean name in Hangul. So when the operator also passes our Korean names, each candidate gets two
 * booleans: {@code hangulSegmentEqualsName} - a Hangul segment of the title (a parenthesised part,
 * or a run of Hangul, digits and spaces) equals our name exactly, whitespace collapsed - and
 * {@code nameInTitle}, plain containment. Only the first is evidence. Containment is reported to
 * be looked at, not used: "경복궁" is also inside the title of a different item such as a ceremony
 * held there. Neither the name nor the title is printed. Both sides are NFC-normalised before any
 * comparison - canonical equivalence, not a guess - and {@code titleNfc} reports whether the title
 * already was, with {@code hangulSegmentLengths} giving the segments' lengths only, so an unexpected
 * {@code false} can be told apart without quoting anything.
 *
 * <p>A known limit of the run branch: in "Changing of the Royal Guard at 경복궁 (수문장 교대식)" the
 * run {@code 경복궁} equals the place's name although the item is the ceremony. Not using runs when a
 * parenthesised Hangul part exists would close that, but it is a new matching rule and is left to a
 * decision rather than made here.
 *
 * <p>The centre coordinate is the place's stored catalog coordinate (a provider-published POI
 * position), never a user's location, so invariant 10 is not in play.
 */
public final class KtoEngServiceMatchProbeMain {

    private static final String APPROVAL = "NULLNULL_KTO_ENG_MATCH_PROBE_APPROVED";
    private static final String PLACES = "NULLNULL_KTO_ENG_MATCH_PROBE_PLACES";
    private static final String NAMES = "NULLNULL_KTO_ENG_MATCH_PROBE_NAMES";
    private static final String OFFICIAL_BASE = "https://apis.data.go.kr/B551011/EngService2";
    static final int RADIUS_METERS = 1000;
    static final int ROWS = 20;
    static final int MAX_PLACES = 5;
    private static final Pattern IDENTIFIER = Pattern.compile("[1-9][0-9]{0,29}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{1,20}");
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final Pattern PARENTHESISED = Pattern.compile("[(（]([^()（）]*)[)）]");
    private static final Pattern HANGUL_RUN = Pattern.compile("[\\p{IsHangul}0-9][\\p{IsHangul}0-9 ]*");

    /** Fields printed as values when they look like codes. Lower-cased: KTO mixes the case. */
    static final Set<String> CODE_FIELDS = Set.of("contentid", "contenttypeid", "areacode", "sigungucode",
            "ldongregncd", "ldongsigngucd", "lclssystm1", "lclssystm2", "lclssystm3", "cat1", "cat2", "cat3");

    private KtoEngServiceMatchProbeMain() {
    }

    record Place(String contentId, BigDecimal latitude, BigDecimal longitude, String koreanName) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> environment = System.getenv();
        if (!"true".equals(environment.get(APPROVAL))) {
            throw new IllegalStateException(APPROVAL + " must be true in the shell running this command;"
                    + " it is not read from .env.local");
        }
        List<Place> places = withNames(places(environment.get(PLACES)), environment.get(NAMES));

        Map<String, String> settings = KtoSmokeEnvironment.load(environment, Path.of(".env.local"));
        KtoSmokeEnvironment.sources(environment, Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_ENG_MATCH_PROBE_SETTINGS " + line));
        String serviceKey = required(settings.get("KTO_SERVICE_KEY"), "KTO_SERVICE_KEY");
        String mobileOs = settings.getOrDefault("KTO_MOBILE_OS", "ETC");
        String mobileApp = settings.getOrDefault("KTO_MOBILE_APP", "Nullnull");

        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            for (int index = 0; index < places.size(); index++) {
                Place place = places.get(index);
                // mapX is longitude and mapY latitude, as on the Korean service.
                URI uri = URI.create(OFFICIAL_BASE + "/locationBasedList2?serviceKey=" + encode(serviceKey)
                        + "&MobileOS=" + encode(mobileOs) + "&MobileApp=" + encode(mobileApp)
                        + "&mapX=" + place.longitude().toPlainString() + "&mapY=" + place.latitude().toPlainString()
                        + "&radius=" + RADIUS_METERS + "&numOfRows=" + ROWS + "&pageNo=1&_type=json");
                System.out.println("KTO_ENG_MATCH_PROBE_PLACE index=" + (index + 1) + " contentId="
                        + place.contentId() + " radiusMeters=" + RADIUS_METERS);
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                System.out.println("KTO_ENG_MATCH_PROBE_HTTP index=" + (index + 1) + " status="
                        + response.statusCode() + " bytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
                report(response.body(), index + 1, place).forEach(System.out::println);
            }
        }
    }

    /** {@code contentId:latitude:longitude}, comma separated, one to {@value #MAX_PLACES}. */
    static List<Place> places(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException(PLACES + " is required: contentId:latitude:longitude,...");
        }
        List<Place> places = new ArrayList<>();
        for (String entry : trimmed.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 3 || !IDENTIFIER.matcher(parts[0].trim()).matches()) {
                throw new IllegalStateException(PLACES + " entries must be contentId:latitude:longitude");
            }
            BigDecimal latitude = coordinate(parts[1], 90);
            BigDecimal longitude = coordinate(parts[2], 180);
            places.add(new Place(parts[0].trim(), latitude, longitude, null));
        }
        if (places.size() > MAX_PLACES) {
            throw new IllegalStateException(PLACES + " allows at most " + MAX_PLACES + " places, one call each");
        }
        return List.copyOf(places);
    }

    /**
     * Our Korean names as {@code contentId=name}, {@code |} separated. Keyed by content id rather than
     * by position, so a list in a different order is matched to the right place instead of silently
     * recording one place's booleans against another; every place must be named exactly once.
     * Optional: without it the report has no name evidence, which is what the first run looked like.
     */
    static List<Place> withNames(List<Place> places, String value) {
        if (value == null || value.isBlank()) {
            return places;
        }
        Map<String, String> names = new java.util.LinkedHashMap<>();
        for (String entry : value.split("\\|", -1)) {
            int separator = entry.indexOf('=');
            String id = separator < 0 ? "" : entry.substring(0, separator).trim();
            String name = separator < 0 ? "" : collapse(entry.substring(separator + 1));
            if (id.isEmpty() || name.isEmpty()) {
                throw new IllegalStateException(NAMES + " entries must be contentId=name");
            }
            if (names.put(id, name) != null) {
                throw new IllegalStateException(NAMES + " names content id " + id + " twice");
            }
        }
        Set<String> expected = new TreeSet<>(places.stream().map(Place::contentId).toList());
        if (!expected.equals(new TreeSet<>(names.keySet()))) {
            throw new IllegalStateException(NAMES + " must name exactly the places' content ids " + expected
                    + ", named " + new TreeSet<>(names.keySet()));
        }
        return places.stream().map(place -> new Place(place.contentId(), place.latitude(), place.longitude(),
                names.get(place.contentId()))).toList();
    }

    /** One place's report. Static so a test can assert what it does and does not contain. */
    static List<String> report(String body, int index, Place place) {
        List<String> lines = new ArrayList<>();
        String prefix = "index=" + index;
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(body);
        } catch (RuntimeException notJson) {
            lines.add("KTO_ENG_MATCH_PROBE_RESULT " + prefix + " verdict=NOT_JSON");
            return lines;
        }
        String resultCode = root.path("response").path("header").path("resultCode").asString("");
        String totalCount = root.path("response").path("body").path("totalCount").asString("");
        if (!"0000".equals(resultCode)) {
            lines.add("KTO_ENG_MATCH_PROBE_ENVELOPE " + prefix + " resultCode=" + printable(resultCode));
            lines.add("KTO_ENG_MATCH_PROBE_RESULT " + prefix + " verdict=PROVIDER_ERROR");
            return lines;
        }
        JsonNode item = root.path("response").path("body").path("items").path("item");
        List<JsonNode> items = new ArrayList<>();
        if (item.isArray()) {
            item.forEach(items::add);
        } else if (item.isObject()) {
            items.add(item);
        }
        lines.add("KTO_ENG_MATCH_PROBE_ENVELOPE " + prefix + " resultCode=0000 totalCount=" + printable(totalCount)
                + " returned=" + items.size());
        if (items.isEmpty()) {
            lines.add("KTO_ENG_MATCH_PROBE_RESULT " + prefix + " verdict=NONE");
            return lines;
        }
        Set<String> fields = new TreeSet<>();
        items.forEach(candidate -> candidate.properties().forEach(field -> fields.add(field.getKey())));
        lines.add("KTO_ENG_MATCH_PROBE_SHAPE " + prefix + " fields=" + String.join(",", fields));
        for (int rank = 0; rank < items.size(); rank++) {
            lines.add(candidate(items.get(rank), prefix + " rank=" + (rank + 1), place));
        }
        lines.add("KTO_ENG_MATCH_PROBE_RESULT " + prefix + " verdict=OBSERVED candidates=" + items.size());
        return lines;
    }

    private static String candidate(JsonNode item, String prefix, Place place) {
        StringBuilder line = new StringBuilder("KTO_ENG_MATCH_PROBE_CANDIDATE ").append(prefix);
        line.append(" distanceMeters=").append(distance(item, place));
        for (Map.Entry<String, JsonNode> field : item.properties()) {
            String name = field.getKey();
            String key = name.toLowerCase(Locale.ROOT);
            JsonNode value = field.getValue();
            if (!value.isValueNode() || value.isNull()) {
                continue;
            }
            String text = value.asString("");
            if (CODE_FIELDS.contains(key)) {
                line.append(' ').append(name).append('=').append(CODE.matcher(text).matches()
                        ? text : text.isEmpty() ? "<empty>" : "<not-a-code length=" + text.length() + ">");
            } else if ("title".equals(key)) {
                line.append(" titleLength=").append(text.length());
                if (!text.isBlank()) {
                    line.append(" titleAsciiLetterPercent=")
                            .append(KtoEngServiceProbeMain.asciiLetterPercent(text))
                            .append(" titleHangul=").append(KtoEngServiceProbeMain.containsHangul(text));
                }
                if (place.koreanName() != null) {
                    String title = Normalizer.normalize(text, Normalizer.Form.NFC);
                    List<String> segments = hangulSegments(title);
                    line.append(" titleNfc=").append(title.equals(text))
                            .append(" hangulSegmentLengths=").append(segments.isEmpty() ? "none"
                                    : String.join(",", segments.stream().map(s -> Integer.toString(s.length())).toList()))
                            .append(" hangulSegmentEqualsName=").append(segments.contains(place.koreanName()))
                            .append(" nameInTitle=").append(title.contains(place.koreanName()));
                }
            }
        }
        return line.toString();
    }

    /**
     * Great-circle distance from the stored place to the item's own mapy/mapx, in whole metres, or
     * {@code unknown} when the item carries no usable coordinate. Computed here rather than read from
     * the provider's {@code dist}, so the number does not depend on a field whose presence is itself
     * one of the things being observed.
     */
    static String distance(JsonNode item, Place place) {
        BigDecimal latitude;
        BigDecimal longitude;
        try {
            latitude = coordinate(item.path("mapy").asString(""), 90);
            longitude = coordinate(item.path("mapx").asString(""), 180);
        } catch (IllegalStateException unusable) {
            return "unknown";
        }
        double phi1 = Math.toRadians(place.latitude().doubleValue());
        double phi2 = Math.toRadians(latitude.doubleValue());
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(longitude.doubleValue() - place.longitude().doubleValue());
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return Long.toString(Math.round(2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)))));
    }

    /**
     * The title's Hangul segments, whitespace collapsed: every parenthesised part that contains
     * Hangul, and every run of Hangul, digits and spaces. "Gyeongbokgung Palace (경복궁)" gives
     * {@code 경복궁}; "Royal Guard Ceremony (경복궁 수문장 교대식)" gives the whole ceremony name and
     * never {@code 경복궁} alone, which is the point.
     */
    static List<String> hangulSegments(String title) {
        List<String> segments = new ArrayList<>();
        var parenthesised = PARENTHESISED.matcher(title);
        while (parenthesised.find()) {
            String inside = collapse(parenthesised.group(1));
            if (KtoEngServiceProbeMain.containsHangul(inside)) {
                segments.add(inside);
            }
        }
        var run = HANGUL_RUN.matcher(title);
        while (run.find()) {
            String candidate = collapse(run.group());
            if (KtoEngServiceProbeMain.containsHangul(candidate)) {
                segments.add(candidate);
            }
        }
        return segments;
    }

    private static String collapse(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
    }

    private static BigDecimal coordinate(String value, int bound) {
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            if (parsed.abs().compareTo(BigDecimal.valueOf(bound)) > 0) {
                throw new IllegalStateException("coordinate out of range");
            }
            return parsed;
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("coordinate is not a number");
        }
    }

    private static String printable(String value) {
        return CODE.matcher(value).matches() ? value : "<length=" + value.length() + ">";
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
