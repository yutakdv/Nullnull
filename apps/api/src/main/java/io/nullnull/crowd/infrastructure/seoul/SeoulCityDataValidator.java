package io.nullnull.crowd.infrastructure.seoul;

import io.nullnull.shared.provider.ProviderResponseValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates one Seoul citydata response before it can become a live-area snapshot. */
public final class SeoulCityDataValidator {

    /**
     * The provider publishes a wall-clock time with no offset ("2026-09-20 15:15"). Seoul is the only
     * place this data describes, so KST is the only reading - but the conversion happens HERE and
     * nowhere else. This repository has already lost nine hours once to an offset-less time that two
     * layers each interpreted (#145), and the way that is avoided is one conversion site with a test
     * on it, not a convention.
     */
    static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter PROVIDER_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** The four steps the manual defines (v8.5, 2026-04). A fifth value is drift, not a new stage. */
    private static final Set<String> CONGESTION_LEVELS = Set.of("여유", "보통", "약간 붐빔", "붐빔");

    private final JsonMapper json = JsonMapper.builder().build();

    public record Validation(ProviderResponseValidator.Verdict verdict, SeoulLiveAreaObservation observation) {
        public boolean accepted() {
            return verdict.outcome() == ProviderResponseValidator.Outcome.OK && observation != null;
        }
    }

    public Validation validate(byte[] response, String expectedAreaName) {
        JsonNode root;
        try {
            root = json.readTree(response);
        } catch (RuntimeException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        }
        JsonNode result = root.path("RESULT");
        if (!"INFO-000".equals(text(result, "CODE"))) {
            return rejected(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        }
        JsonNode city = root.path("CITYDATA");
        String areaName = text(city, "AREA_NM");
        String areaCode = text(city, "AREA_CD");
        if (areaName == null || areaCode == null || !areaName.equals(expectedAreaName)) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        }
        JsonNode live = city.path("LIVE_PPLTN_STTS");
        if (!live.isArray() || live.isEmpty()) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        }
        JsonNode population = live.get(0);

        // REPLACE_YN = "Y" MEANS THE PROVIDER SUBSTITUTED THIS READING, AND WE DO NOT KNOW WITH WHAT.
        //
        // Searched for the definition and did not find it: the dataset detail page carries no output
        // field table, and the manual v8.5 extracts to text with zero occurrences of REPLACE_YN,
        // REPLACE or 대체 - its API field tables are images, so only AREA_NM, AREA_CD and SUB_STTS come
        // out at all. The only token of that shape anywhere in the text is LIVE_YN.
        //
        // The three meanings it could have are handled differently: a neighbouring area's value is a
        // mapping problem, the previous observation is a freshness problem, and an estimate is not an
        // observation at all. Storing it as LIVE would advertise a substitute as a measurement under
        // every one of them, so it is refused here. Relaxing this later is easy; discovering we
        // published substitutes as live readings is not.
        if ("Y".equalsIgnoreCase(text(population, "REPLACE_YN"))) {
            // PROVISIONAL OUTCOME. None of the seven values means "the provider says this is a
            // substitute": an eighth would have to move ProviderResponseValidator.Outcome,
            // IngestAudit.ValidationResult and the api_ingest_validation_check CHECK together, and
            // that CHECK is in an applied migration. Raised for a vocabulary decision.
            return rejected(ProviderResponseValidator.Outcome.PROVIDER_ERROR);
        }

        String level = text(population, "AREA_CONGEST_LVL");
        if (level == null || !CONGESTION_LEVELS.contains(level)) {
            return rejected(ProviderResponseValidator.Outcome.ENUM_DRIFT);
        }
        Instant observedAt;
        try {
            observedAt = providerInstant(text(population, "PPLTN_TIME"));
        } catch (DateTimeParseException | NullPointerException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
        }

        List<SeoulLiveAreaObservation.ForecastPoint> points = new ArrayList<>();
        if ("Y".equalsIgnoreCase(text(population, "FCST_YN"))) {
            JsonNode forecast = population.path("FCST_PPLTN");
            if (!forecast.isArray()) {
                return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
            }
            for (JsonNode point : forecast) {
                String forecastLevel = text(point, "FCST_CONGEST_LVL");
                if (forecastLevel == null || !CONGESTION_LEVELS.contains(forecastLevel)) {
                    return rejected(ProviderResponseValidator.Outcome.ENUM_DRIFT);
                }
                try {
                    points.add(new SeoulLiveAreaObservation.ForecastPoint(
                            providerInstant(text(point, "FCST_TIME")), forecastLevel));
                } catch (DateTimeParseException | NullPointerException failure) {
                    return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT);
                }
            }
        }
        return new Validation(new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.OK, 0),
                new SeoulLiveAreaObservation(areaCode, areaName, level, observedAt,
                        issueId(areaCode, observedAt, points), points));
    }

    /**
     * The identity of one forecast publication. Three inputs, and each one stops a different mistake:
     *
     * <ul>
     *   <li>{@code areaCode} - collision between AREAS. KTO derives its issue from (targetAt, value)
     *       pairs with no subject in the hash; its values are decimal indices so two places rarely
     *       match, but Seoul's forecast points are four Korean words and two quiet areas producing the
     *       same twelve-slot sequence is ordinary. Without this, unrelated areas would share an issue
     *       id, and TemporalComparisonPolicy pairs points that do.
     *   <li>{@code observedAt} - confusion between PUBLICATIONS. KTO cannot do this: its response
     *       carries no observation time, so content is all it has. Seoul gives one, so re-collecting
     *       the same publication yields one issue and a genuinely new one yields another.
     *   <li>the points themselves - a SILENT VALUE CHANGE. If the numbers move, the issue must move,
     *       or an old forecast and a new one become comparable.
     * </ul>
     *
     * <p>Remove any one and the other two still cover most cases, which is exactly why each is named.
     * docs/data/SOURCE_CATALOG.md section 5 is the canonical statement; this comment points at it.
     */
    private static String issueId(String areaCode, Instant observedAt,
            List<SeoulLiveAreaObservation.ForecastPoint> points) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(areaCode.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1e);
            digest.update(observedAt.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1e);
            for (SeoulLiveAreaObservation.ForecastPoint point : points) {
                digest.update(point.targetAt().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
                digest.update(point.congestionLevel().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1e);
            }
            return "seoul-citydata-" + java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    private static Instant providerInstant(String value) {
        return LocalDateTime.parse(value, PROVIDER_TIME).atZone(PROVIDER_ZONE).toInstant();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String normalized = value.asString().trim();
        return normalized.isEmpty() || normalized.length() > 300 ? null : normalized;
    }

    private static Validation rejected(ProviderResponseValidator.Outcome outcome) {
        return new Validation(new ProviderResponseValidator.Verdict(outcome, 1), null);
    }
}
