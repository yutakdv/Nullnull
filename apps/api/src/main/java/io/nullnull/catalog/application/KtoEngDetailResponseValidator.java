package io.nullnull.catalog.application;

import io.nullnull.shared.provider.ProviderResponseValidator;
import io.nullnull.shared.provider.ProviderResponseValidator.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one KTO {@code EngService2/detailCommon2} answer for a linked English record (BA-086).
 *
 * <p>It differs from {@link KtoDetailResponseValidator} in one place that matters: a well-formed
 * answer with exactly zero items is {@link Gone}, not drift. For a record the owner already reviewed,
 * "the dataset no longer carries it" is the provider's answer, and the English text built from it has
 * to stop being shown. Reading that answer as drift would quarantine the whole English source and leave
 * the stale text in place, because the read gate does not look at quarantine. An answer that does not
 * say how many items it has is not a deletion and stays drift.
 */
public final class KtoEngDetailResponseValidator {

    private final JsonMapper json;

    public KtoEngDetailResponseValidator() {
        this(JsonMapper.builder().build());
    }

    KtoEngDetailResponseValidator(JsonMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** What one answer says: the record, its absence, or nothing trustworthy. */
    public sealed interface Result permits Found, Gone, Rejected {
        ProviderResponseValidator.Verdict verdict();

        int responseCount();
    }

    public record Found(KtoEngRecord record) implements Result {
        public Found {
            Objects.requireNonNull(record, "record");
        }

        @Override
        public ProviderResponseValidator.Verdict verdict() {
            return new ProviderResponseValidator.Verdict(Outcome.OK, 0);
        }

        @Override
        public int responseCount() {
            return 1;
        }
    }

    public record Gone() implements Result {
        @Override
        public ProviderResponseValidator.Verdict verdict() {
            return new ProviderResponseValidator.Verdict(Outcome.OK, 0);
        }

        @Override
        public int responseCount() {
            return 0;
        }
    }

    public record Rejected(Outcome outcome, int responseCount) implements Result {
        public Rejected {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == Outcome.OK) {
                throw new IllegalArgumentException("a rejection needs a failing outcome");
            }
        }

        @Override
        public ProviderResponseValidator.Verdict verdict() {
            return new ProviderResponseValidator.Verdict(outcome, 1);
        }
    }

    public Result validate(byte[] response, KtoPlaceRequest expected) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(expected, "expected");
        JsonNode root;
        try {
            root = json.readTree(new String(response, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 0);
        }
        if (topLevelProviderError(root)) {
            return new Rejected(Outcome.PROVIDER_ERROR, 0);
        }
        JsonNode envelope = root.path("response");
        JsonNode header = envelope.path("header");
        if (!envelope.isObject() || !header.isObject()) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 0);
        }
        if (!"0000".equals(header.path("resultCode").asString())) {
            return new Rejected(Outcome.PROVIDER_ERROR, 0);
        }
        JsonNode body = envelope.path("body");
        Integer total = count(body.get("totalCount"));
        if (!body.isObject() || total == null) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 0);
        }
        JsonNode items = body.path("items");
        if (total == 0) {
            return noItem(items) ? new Gone() : new Rejected(Outcome.SCHEMA_DRIFT, 0);
        }
        JsonNode item = singleItem(items.path("item"));
        if (total != 1 || !items.isObject() || !item.isObject()) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 0);
        }
        if (!expected.contentId().equals(item.path("contentid").asString())
                || !expected.contentTypeId().equals(item.path("contenttypeid").asString())) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 1);
        }
        try {
            BigDecimal longitude = coordinate(item, "mapx");
            BigDecimal latitude = coordinate(item, "mapy");
            if ((latitude == null) != (longitude == null)) {
                return new Rejected(Outcome.RANGE, 1);
            }
            if (legacyCodesOnly(item)) {
                return new Rejected(Outcome.SCHEMA_DRIFT, 1);
            }
            return new Found(KtoEngRecord.of(expected.contentId(), expected.contentTypeId(), optional(item, "title"),
                    optional(item, "addr1"), latitude, longitude, optional(item, "lclsSystm1"),
                    optional(item, "lDongRegnCd"), optional(item, "lDongSignguCd")));
        } catch (CoordinateOutOfRangeException failure) {
            return new Rejected(Outcome.RANGE, 1);
        } catch (IllegalArgumentException | NullPointerException failure) {
            return new Rejected(Outcome.SCHEMA_DRIFT, 1);
        }
    }

    /** Exactly the provider's count, as a number or a numeric string; anything else is unknown. */
    private static Integer count(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.intValue();
        }
        if (node.isString() && node.asString().trim().matches("[0-9]{1,9}")) {
            return Integer.valueOf(node.asString().trim());
        }
        return null;
    }

    /** The shapes KTO uses for "no items": an empty string, or an items object with no item in it. */
    private static boolean noItem(JsonNode items) {
        if (items.isMissingNode() || items.isNull()) {
            return true;
        }
        if (items.isString()) {
            return items.asString().isBlank();
        }
        if (!items.isObject()) {
            return false;
        }
        JsonNode item = items.path("item");
        return item.isMissingNode() || item.isNull() || item.isArray() && item.isEmpty()
                || item.isString() && item.asString().isBlank();
    }

    private static boolean legacyCodesOnly(JsonNode item) {
        boolean current = optional(item, "lclsSystm1") != null || optional(item, "lDongRegnCd") != null
                || optional(item, "lDongSignguCd") != null;
        boolean legacy = optional(item, "cat1") != null || optional(item, "areacode") != null
                || optional(item, "sigungucode") != null;
        return legacy && !current;
    }

    private static boolean topLevelProviderError(JsonNode root) {
        if (!root.isObject() || root.has("response") || !root.has("resultCode")) {
            return false;
        }
        return !"0000".equals(root.path("resultCode").asString());
    }

    private static JsonNode singleItem(JsonNode candidate) {
        if (candidate.isArray()) {
            return candidate.size() == 1 ? candidate.get(0) : candidate;
        }
        return candidate;
    }

    private static String optional(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString() && !value.isNumber()) {
            throw new IllegalArgumentException(field + " not scalar");
        }
        String text = value.asString().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal coordinate(JsonNode item, String field) {
        String raw = optional(item, field);
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal coordinate = new BigDecimal(raw);
            boolean latitude = "mapy".equals(field);
            BigDecimal minimum = latitude ? BigDecimal.valueOf(-90) : BigDecimal.valueOf(-180);
            BigDecimal maximum = latitude ? BigDecimal.valueOf(90) : BigDecimal.valueOf(180);
            if (coordinate.compareTo(minimum) < 0 || coordinate.compareTo(maximum) > 0) {
                throw new CoordinateOutOfRangeException();
            }
            return coordinate.setScale(6, RoundingMode.HALF_UP);
        } catch (NumberFormatException failure) {
            throw new CoordinateOutOfRangeException();
        }
    }

    private static final class CoordinateOutOfRangeException extends RuntimeException {
    }
}
