package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.domain.ComparisonReasonCode;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One comparison reason vocabulary, declared in places that cannot see each other.
 *
 * <p>{@code docs/data/SOURCE_CATALOG.md} §9 is the product definition, {@link ComparisonReasonCode}
 * is what the server may emit, and {@code docs/api/openapi.yaml} plus
 * {@code packages/contracts/fixtures} are what Frontend reads and mocks against. The contract does
 * NOT constrain the field - {@code comparisonReasonCode} is {@code type: [string, "null"]} in both
 * {@code DataProvenance} and {@code OptimizationMetrics} - so the published examples are the only
 * place the vocabulary appears on the contract side, and nothing was comparing them to anything.
 *
 * <p>Two values had already drifted in, which is what a set comparison in both directions is for:
 * {@code NO_READING} in a feed example and its two fixtures, for a snapshot whose
 * {@code sourceState} is UNAVAILABLE - a shape {@code CrowdProvenanceProjectionTest} already pins
 * to MISSING_PROVENANCE; and {@code SOURCE_MISMATCH} in nine proposal test fixtures, where §9's
 * word for "source 다름" is DIFFERENT_SOURCE. Neither is emittable: the only producers of this
 * field are {@code CrowdProvenanceProjection.project} and {@code .compare}, and both return a
 * {@link ComparisonReasonCode} constant. An example a client branches on that the server can never
 * send is the same defect as a state the server holds and cannot serialise.
 *
 * <p>Deliberately NOT done here: putting {@code enum} on the contract field. The one other
 * vocabulary whose source of truth sits outside the server keeps its list in an extension
 * ({@code x-nullnull-interest-codes}) precisely so that adding a value is not breaking, and whether
 * that applies here is a Frontend-facing decision this check does not get to make. What it does is
 * make the drift visible before it ships.
 *
 * <p>No acceptance ID leads this class, the same as {@code check_container_test_inputs.py}: no card
 * owns it, and borrowing one is the shape #195 describes. It runs because the suite runs it.
 */
@DisplayName("comparison reason code vocabulary")
class ComparisonReasonCodeVocabularyTest {

    /** A §9 table row: the code is the first cell and is written in backticks. */
    private static final Pattern CATALOG_ROW = Pattern.compile("^\\|\\s*`([A-Z_]+)`\\s*\\|", Pattern.MULTILINE);

    /** §9 runs to the next level-two heading. Anchored on the number so a retitle does not silently empty it. */
    private static final Pattern SECTION_NINE = Pattern.compile("(?s)\\n## 9\\.[^\\n]*\\n(.*?)\\n## ");

    private static final String FIELD = "comparisonReasonCode";

    @Test
    @DisplayName("the catalog table and the server constants are the same set")
    void theProductDefinitionAndTheServerVocabularyAgree() {
        Set<String> catalog = catalogCodes();
        Set<String> server = serverCodes();

        assertThat(catalog).as("§9 must actually enumerate codes").isNotEmpty();
        assertThat(server).as("ComparisonReasonCode must actually declare constants").isNotEmpty();
        // Both directions. A code in §9 and not in the server is a product promise nothing can keep;
        // a constant not in §9 is a value the server emits that no document explains.
        assertThat(server).containsExactlyInAnyOrderElementsOf(catalog);
    }

    @Test
    @DisplayName("every constant's value is its own name")
    void noConstantCarriesADifferentStringThanItsName() {
        // These are Strings, not an enum, so a typo in the value compiles and is emitted silently
        // while every name-based reading of the class still looks right.
        constants().forEach((name, value) -> assertThat(value)
                .as("ComparisonReasonCode.%s must carry its own name", name).isEqualTo(name));
    }

    @Test
    @DisplayName("every reason code published in the contract is in the vocabulary")
    void theContractExamplesUseOnlyPublishedCodes() {
        List<String> published = reasonCodesIn(openApiTree());

        assertThat(published).as("the contract must carry at least one %s example", FIELD).isNotEmpty();
        assertThat(published).allSatisfy(code -> assertThat(serverCodes())
                .as("openapi.yaml publishes %s=%s, which the server cannot emit", FIELD, code)
                .contains(code));
    }

    @Test
    @DisplayName("every reason code in the Frontend fixtures is in the vocabulary")
    void theMockedFixturesUseOnlyPublishedCodes() {
        List<String> mocked = fixtureCodes();

        assertThat(mocked).as("the fixtures must carry at least one %s value", FIELD).isNotEmpty();
        assertThat(mocked).allSatisfy(code -> assertThat(serverCodes())
                .as("a fixture mocks %s=%s, which the server cannot emit", FIELD, code)
                .contains(code));
    }

    private static Set<String> catalogCodes() {
        String catalog = read(Path.of("../../docs/data/SOURCE_CATALOG.md"));
        Matcher section = SECTION_NINE.matcher(catalog);
        if (!section.find()) {
            return Set.of();
        }
        Set<String> codes = new TreeSet<>();
        Matcher row = CATALOG_ROW.matcher(section.group(1));
        while (row.find()) {
            codes.add(row.group(1));
        }
        return codes;
    }

    private static Set<String> serverCodes() {
        return new TreeSet<>(constants().values());
    }

    private static Map<String, String> constants() {
        Map<String, String> declared = new LinkedHashMap<>();
        for (Field field : ComparisonReasonCode.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || field.getType() != String.class) {
                continue;
            }
            try {
                declared.put(field.getName(), (String) field.get(null));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("cannot read " + field.getName(), exception);
            }
        }
        return declared;
    }

    /**
     * Every value carried under a {@code comparisonReasonCode} key anywhere in the tree.
     *
     * <p>Walking the parsed document rather than the text keeps the schema declarations out without
     * a second rule: there the key's value is a mapping, not a string. {@code null} is skipped
     * because it is legal and means "no reason" - V029's CHECK makes it the required value for an
     * eligible proposal - so a guard that rejected it would refuse a correct example.
     */
    private static List<String> reasonCodesIn(Object node) {
        List<String> found = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (FIELD.equals(key) && value instanceof String text) {
                    found.add(text);
                } else {
                    found.addAll(reasonCodesIn(value));
                }
            });
        } else if (node instanceof Iterable<?> items) {
            items.forEach(item -> found.addAll(reasonCodesIn(item)));
        }
        return found;
    }

    private static List<String> fixtureCodes() {
        Path root = Path.of(System.getProperty("nullnull.fixtures.path", "../../packages/contracts/fixtures"));
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".json")).sorted()
                    .forEach(path -> collect(mapper.readTree(read(path)), found));
        } catch (IOException exception) {
            throw new IllegalStateException("the contract fixtures must be readable from the api module", exception);
        }
        return found;
    }

    private static void collect(JsonNode node, List<String> found) {
        if (node.isObject()) {
            node.properties().forEach(property -> {
                if (FIELD.equals(property.getKey()) && property.getValue().isString()) {
                    found.add(property.getValue().asString());
                } else {
                    collect(property.getValue(), found);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collect(item, found));
        }
    }

    private static Object openApiTree() {
        String property = System.getProperty("nullnull.openapi.path");
        Path path = property == null || property.isBlank()
                ? Path.of("../../docs/api/openapi.yaml") : Path.of(property);
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(16 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options)).load(read(path));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + path, exception);
        }
    }
}
