package io.nullnull.shared.provider;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates transport-success bodies before any canonical row or snapshot can be written. */
public final class ProviderResponseValidator {

    public enum Outcome { OK, SCHEMA_DRIFT, ENUM_DRIFT, RANGE, TIME_SKEW, PROVIDER_ERROR }

    @FunctionalInterface
    public interface SemanticRule {
        Optional<Outcome> validate(JsonNode body);
    }

    public record Verdict(Outcome outcome, int errorCount) {
        public boolean permitsCanonicalWrite() {
            return outcome == Outcome.OK;
        }
    }

    private final JsonMapper json;
    private final Schema schema;

    public ProviderResponseValidator(JsonMapper json, String schemaDocument) {
        this.json = json;
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaDocument, InputFormat.JSON);
    }

    public Verdict validate(byte[] body, boolean incidentActive, List<SemanticRule> semanticRules) {
        JsonNode tree;
        try {
            tree = json.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            return new Verdict(Outcome.SCHEMA_DRIFT, 1);
        }
        if (providerError(tree) || incidentActive) {
            return new Verdict(Outcome.PROVIDER_ERROR, 1);
        }
        List<Error> errors = schema.validate(tree);
        if (!errors.isEmpty()) {
            Outcome outcome = errors.stream().anyMatch(error -> "enum".equals(error.getKeyword()))
                    ? Outcome.ENUM_DRIFT : Outcome.SCHEMA_DRIFT;
            return new Verdict(outcome, errors.size());
        }
        for (SemanticRule rule : semanticRules) {
            Optional<Outcome> result = rule.validate(tree);
            if (result.isPresent()) {
                Outcome outcome = result.orElseThrow();
                if (outcome != Outcome.RANGE && outcome != Outcome.TIME_SKEW) {
                    throw new IllegalArgumentException("semantic rules may return RANGE or TIME_SKEW only");
                }
                return new Verdict(outcome, 1);
            }
        }
        return new Verdict(Outcome.OK, 0);
    }

    private static boolean providerError(JsonNode tree) {
        JsonNode header = tree.path("response").path("header");
        String code = header.path("resultCode").asText("");
        if (code.isBlank()) {
            code = tree.path("resultCode").asText("");
        }
        String normalized = code.toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("22") || normalized.equals("23") || normalized.contains("AUTH")
                || normalized.contains("SERVICE_KEY");
    }
}
