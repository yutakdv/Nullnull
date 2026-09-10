package io.nullnull.catalog.application;

import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.shared.provider.ProviderResponseValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural and semantic validator for the one C2-approved KTO operation, {@code detailCommon2}.
 * A response must validate before its normalized projection can be persisted.
 */
public final class KtoDetailResponseValidator {

    private final JsonMapper json;

    public KtoDetailResponseValidator() {
        this(JsonMapper.builder().build());
    }

    KtoDetailResponseValidator(JsonMapper json) {
        this.json = json;
    }

    public Validation validate(byte[] response, KtoPlaceRequest expected, long sourceRegistryVersion,
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
        JsonNode items = body.path("items");
        JsonNode item = singleItem(items.path("item"));
        if (!body.isObject() || !items.isObject() || !item.isObject() || body.path("totalCount").asInt(-1) != 1) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, 0);
        }
        if (!expected.contentId().equals(item.path("contentid").asString())
                || !expected.contentTypeId().equals(item.path("contenttypeid").asString())) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, 1);
        }

        try {
            BigDecimal longitude = coordinate(item, "mapx");
            BigDecimal latitude = coordinate(item, "mapy");
            if ((latitude == null) != (longitude == null)) {
                return rejected(ProviderResponseValidator.Outcome.RANGE, 1);
            }
            KtoPlaceSnapshot snapshot = KtoPlaceSnapshot.accepted(sourceRegistryVersion, collectorRunId,
                    expected.contentId(), expected.contentTypeId(), required(item, "title"), optional(item, "cat1"),
                    optional(item, "areacode"), optional(item, "sigungucode"), optional(item, "addr1"), latitude,
                    longitude, fetchedAt, staleAfter);
            return new Validation(new ProviderResponseValidator.Verdict(ProviderResponseValidator.Outcome.OK, 0),
                    snapshot, 1);
        } catch (CoordinateOutOfRangeException failure) {
            return rejected(ProviderResponseValidator.Outcome.RANGE, 1);
        } catch (IllegalArgumentException failure) {
            return rejected(ProviderResponseValidator.Outcome.SCHEMA_DRIFT, 1);
        }
    }

    private static Validation rejected(ProviderResponseValidator.Outcome outcome, int responseCount) {
        return new Validation(new ProviderResponseValidator.Verdict(outcome, 1), null, responseCount);
    }

    private static boolean topLevelProviderError(JsonNode root) {
        if (!root.isObject() || root.has("response") || !root.has("resultCode")) {
            return false;
        }
        String code = root.path("resultCode").asString();
        return !"0000".equals(code);
    }

    private static JsonNode singleItem(JsonNode candidate) {
        if (candidate.isArray()) {
            return candidate.size() == 1 ? candidate.get(0) : candidate;
        }
        return candidate;
    }

    private static String required(JsonNode item, String field) {
        String value = optional(item, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " missing");
        }
        return value;
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

    public record Validation(ProviderResponseValidator.Verdict verdict, KtoPlaceSnapshot snapshot,
            int responseCount) {
        public boolean accepted() {
            return verdict.permitsCanonicalWrite() && snapshot != null;
        }
    }

    private static final class CoordinateOutOfRangeException extends RuntimeException {
    }
}
