package io.nullnull.crowd.application;

import io.nullnull.shared.provider.ProviderResponseValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the KTO date-level concentration response before a normalized forecast snapshot can be
 * written. The provider does not publish an observation timestamp, so this class never invents one.
 */
public final class KtoForecastResponseValidator {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KTO_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_FORECAST_DAYS = 30;
    private static final int MAX_RECORDS = 31;

    private final JsonMapper json;

    public KtoForecastResponseValidator() {
        this(JsonMapper.builder().build());
    }

    KtoForecastResponseValidator(JsonMapper json) {
        this.json = json;
    }

    public Validation validate(byte[] response, KtoForecastRequest expected, long sourceRegistryVersion,
            UUID collectorRunId, Instant fetchedAt, Duration staleAfter) {
        JsonNode root;
        try {
            root = json.readTree(new String(response, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, 0);
        }
        if (topLevelProviderError(root)) {
            return rejected(ProviderResponseValidator.Outcome.PROVIDER_ERROR, 0);
        }
        JsonNode envelope = root.path("response");
        JsonNode header = envelope.path("header");
        if (!envelope.isObject() || !header.isObject()) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, 0);
        }
        if (!"0000".equals(header.path("resultCode").asString())) {
            return rejected(ProviderResponseValidator.Outcome.PROVIDER_ERROR, 0);
        }
        JsonNode body = envelope.path("body");
        int total = body.path("totalCount").asInt(-1);
        List<JsonNode> items = items(body.path("items").path("item"));
        if (!body.isObject() || total < 0 || items == null || total != items.size() || total > MAX_RECORDS) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, Math.max(0, total));
        }
        if (items.isEmpty()) {
            return acceptedNoCoverage();
        }

        try {
            List<KtoForecastSnapshotSet.ForecastPoint> points = new ArrayList<>();
            Set<Instant> targets = new HashSet<>();
            LocalDate firstAllowed = LocalDate.ofInstant(fetchedAt, SEOUL);
            LocalDate lastAllowed = firstAllowed.plusDays(MAX_FORECAST_DAYS);
            for (JsonNode item : items) {
                requireEqual(expected.areaCode(), scalar(item, "areaCd"));
                // The provider echoes back what was sent, and what is sent is the JOINED code
                // (KtoForecastRequest.signguRequestCode), so comparing the raw stored sigungu code
                // here would reject every real response.
                requireEqual(expected.signguRequestCode(), scalar(item, "signguCd"));
                requireEqual(expected.touristSiteName(), scalar(item, "tAtsNm"));
                LocalDate targetDate = date(scalar(item, "baseYmd"));
                if (targetDate.isBefore(firstAllowed) || targetDate.isAfter(lastAllowed)) {
                    return rejected(ProviderResponseValidator.Outcome.RANGE, items.size());
                }
                Instant targetAt = targetDate.atStartOfDay(SEOUL).toInstant();
                if (!targets.add(targetAt)) {
                    return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, items.size());
                }
                points.add(new KtoForecastSnapshotSet.ForecastPoint(UUID.randomUUID(), targetAt,
                        concentrationRate(scalar(item, "cnctrRate"))));
            }
            points.sort(Comparator.comparing(KtoForecastSnapshotSet.ForecastPoint::targetAt));
            String payloadHash = payloadHash(points);
            String issue = "kto-tats-" + payloadHash.substring(0, 32);
            KtoForecastSnapshotSet snapshotSet = new KtoForecastSnapshotSet(UUID.randomUUID(), collectorRunId,
                    sourceRegistryVersion, issue, issue, "kto-tats-cnctr-rate-v4.1", payloadHash, fetchedAt,
                    fetchedAt.plus(staleAfter), points);
            return new Validation(new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.OK, 0),
                    snapshotSet, items.size());
        } catch (DateTimeParseException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, items.size());
        } catch (RateOutOfRangeException failure) {
            return rejected(ProviderResponseValidator.Outcome.RANGE, items.size());
        } catch (IllegalArgumentException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, items.size());
        }
    }

    private static Validation acceptedNoCoverage() {
        return new Validation(new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.OK, 0), null, 0);
    }

    private static Validation rejected(ProviderResponseValidator.Outcome outcome, int responseCount) {
        return new Validation(new ProviderResponseValidator.Verdict(outcome, 1), null, responseCount);
    }

    private static boolean topLevelProviderError(JsonNode root) {
        if (!root.isObject() || root.has("response") || !root.has("resultCode")) {
            return false;
        }
        return !"0000".equals(root.path("resultCode").asString());
    }

    private static List<JsonNode> items(JsonNode candidate) {
        if (candidate.isMissingNode() || candidate.isNull()) {
            return List.of();
        }
        if (candidate.isObject()) {
            return List.of(candidate);
        }
        if (!candidate.isArray()) {
            return null;
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode value : candidate) {
            if (!value.isObject()) {
                return null;
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String scalar(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull() || (!value.isTextual() && !value.isNumber())) {
            throw new IllegalArgumentException(field + " is missing or non-scalar");
        }
        String normalized = value.asString().trim();
        if (normalized.isEmpty() || normalized.length() > 300 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static void requireEqual(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("provider place mapping differs from canonical KTO mapping");
        }
    }

    private static LocalDate date(String value) {
        if (!value.matches("[0-9]{8}")) {
            throw new DateTimeParseException("KTO baseYmd is invalid", value, 0);
        }
        return LocalDate.parse(value, KTO_DATE);
    }

    private static BigDecimal concentrationRate(String raw) {
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.scale() > 4 || value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new RateOutOfRangeException();
            }
            return value.setScale(Math.max(0, value.scale()), RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new RateOutOfRangeException();
        }
    }

    private static String payloadHash(List<KtoForecastSnapshotSet.ForecastPoint> points) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (KtoForecastSnapshotSet.ForecastPoint point : points) {
                digest.update(point.targetAt().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
                digest.update(point.value().stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1e);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    public record Validation(ProviderResponseValidator.Verdict verdict, KtoForecastSnapshotSet snapshotSet,
            int responseCount) {
        public boolean accepted() {
            return verdict.permitsCanonicalWrite();
        }

        public boolean hasCoverage() {
            return snapshotSet != null;
        }
    }

    private static final class RateOutOfRangeException extends RuntimeException {
    }
}
