package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * CMP-LOC-001: no request the contract accepts carries a coordinate.
 *
 * <p>Sending a person's position to the server can make the service a location-based service that must
 * be registered, whether or not it is stored (docs/contest/COMPETITION_COMPLIANCE_MATRIX.md), and the
 * submission profile keeps location off. That is true of the contract today, and nothing checked it: a
 * new operation could add a latitude to a request body and every suite would stay green. This walks every
 * request body and parameter of docs/api/openapi.yaml, and every event property of
 * docs/contracts/events.schema.json (what ingestEventBatch accepts), and refuses a coordinate found by its
 * name or by its shape - a number bounded to plus or minus 90 or 180 is a coordinate whatever it is
 * called.
 *
 * <p>One exception exists and is registered below rather than tolerated: queryLiveAreas' coarse map
 * viewport (docs/security/PRIVACY_REQUIREMENTS.md). It is bounds the person panned to, not their
 * position, rounded to three decimals. It stays an exception only while the contract still pins that
 * rounding, which is checked here as well - the day it loses {@code multipleOf: 0.001} it is a precise
 * coordinate and this goes red.
 *
 * <p>Responses are not in scope: places answer with their own public coordinates, which is not the
 * person's position.
 */
@DisplayName("CMP-LOC-001 no request carries a coordinate")
class LocationInputContractTest {

    /** Words that name a coordinate, matched against each word of a property or parameter name. */
    private static final Set<String> COORDINATE_WORDS = Set.of("lat", "latitude", "lng", "lon", "longitude",
            "coord", "coords", "coordinate", "coordinates", "geo", "geolocation", "geohash", "gps", "location",
            "locations", "altitude", "bbox", "mapx", "mapy", "viewport", "north", "south", "east", "west");

    /** The one registered exception: queryLiveAreas' coarse viewport and the four bounds inside it. */
    private static final String COARSE_VIEWPORT_OPERATION = "queryLiveAreas";
    private static final Pattern COARSE_VIEWPORT_PATH = Pattern.compile("viewport(\\.(west|south|east|north))?");

    private static final Pattern WORD = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+");

    @Test
    @DisplayName("CMP-LOC-001 no request body field or parameter is a coordinate, except the registered coarse viewport")
    void noRequestCarriesACoordinate() {
        Map<String, Object> api = openApi();
        List<Field> fields = requestFields(api);

        List<String> coordinates = new ArrayList<>();
        for (Field field : fields) {
            if (field.coordinate() && !coarseViewport(field)) {
                String finding = field.operation() + " " + field.path();
                if (!coordinates.contains(finding)) {
                    coordinates.add(finding);
                }
            }
        }
        assertThat(coordinates).as("request fields that are coordinates").isEmpty();

        // Not vacuous: the walk reached request bodies and parameters, including the exception itself.
        Set<String> names = new TreeSet<>();
        fields.forEach(field -> names.add(field.operation() + " " + field.path()));
        assertThat(fields).hasSizeGreaterThan(60);
        assertThat(names).contains("createTrip startDate", "parseTripImport rawText",
                "replaceTripItem replacementPlaceId", "getTrip tripId", "queryLiveAreas viewport.north");
    }

    @Test
    @DisplayName("CMP-LOC-001 the coarse viewport stays rounded to three decimals, or it is not an exception")
    void theCoarseViewportStaysCoarse() {
        Map<String, Object> schemas = map(map(openApi().get("components")).get("schemas"));
        Map<String, Object> viewport = map(map(schemas.get("CoarseViewport")).get("properties"));
        assertThat(viewport.keySet()).containsExactlyInAnyOrder("west", "south", "east", "north");
        for (Map.Entry<String, Object> bound : viewport.entrySet()) {
            assertThat(((Number) map(bound.getValue()).get("multipleOf")).doubleValue())
                    .as("CoarseViewport.%s multipleOf", bound.getKey()).isEqualTo(0.001);
        }
    }

    @Test
    @DisplayName("CMP-LOC-001 no event property is a coordinate")
    void noEventCarriesACoordinate() throws IOException {
        Object events = JsonMapper.builder().build().readValue(Files.readString(eventSchemaPath(),
                StandardCharsets.UTF_8), Object.class);
        List<Field> fields = new ArrayList<>();
        walk("ingestEventBatch", "", events, map(events), new HashSet<>(), fields);

        // One property reached through several references is one finding.
        assertThat(fields.stream().filter(Field::coordinate).map(Field::path).distinct().toList())
                .as("event properties that are coordinates").isEmpty();
        assertThat(fields.stream().map(Field::path).map(path -> path.substring(path.lastIndexOf('.') + 1)))
                .contains("eventId", "placeId", "occurredAt");
    }

    /** A property or parameter of one request, with the dotted path from its body or parameter list. */
    private record Field(String operation, String path, Map<String, Object> schema) {

        boolean coordinate() {
            String name = path.substring(path.lastIndexOf('.') + 1);
            Matcher words = WORD.matcher(name.replace('-', ' ').replace('_', ' '));
            while (words.find()) {
                if (COORDINATE_WORDS.contains(words.group().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return degreeBounded(schema);
        }

        /** A number whose bounds are a latitude's or a longitude's, whatever it is called. */
        private static boolean degreeBounded(Map<String, Object> schema) {
            if (schema == null || !(schema.get("minimum") instanceof Number min)
                    || !(schema.get("maximum") instanceof Number max)) {
                return false;
            }
            double low = min.doubleValue();
            double high = max.doubleValue();
            return (low == -90 && high == 90) || (low == -180 && high == 180);
        }
    }

    private static boolean coarseViewport(Field field) {
        return COARSE_VIEWPORT_OPERATION.equals(field.operation())
                && COARSE_VIEWPORT_PATH.matcher(field.path()).matches();
    }

    private static List<Field> requestFields(Map<String, Object> api) {
        List<Field> fields = new ArrayList<>();
        for (Object item : map(api.get("paths")).values()) {
            Map<String, Object> pathItem = map(item);
            for (Map.Entry<String, Object> entry : pathItem.entrySet()) {
                if (!Set.of("get", "put", "post", "delete", "patch").contains(entry.getKey())) {
                    continue;
                }
                Map<String, Object> operation = map(entry.getValue());
                String operationId = String.valueOf(operation.get("operationId"));
                List<Object> parameters = new ArrayList<>(list(pathItem.get("parameters")));
                parameters.addAll(list(operation.get("parameters")));
                for (Object parameter : parameters) {
                    Map<String, Object> resolved = resolve(api, map(parameter));
                    fields.add(new Field(operationId, String.valueOf(resolved.get("name")),
                            resolved.get("schema") == null ? null : resolve(api, map(resolved.get("schema")))));
                }
                if (operation.get("requestBody") != null) {
                    Map<String, Object> body = resolve(api, map(operation.get("requestBody")));
                    for (Object media : map(body.get("content")).values()) {
                        walk(operationId, "", map(media).get("schema"), api, new HashSet<>(), fields);
                    }
                }
            }
        }
        return fields;
    }

    /** Every property under a schema, through references, combinations, items and nested objects. */
    private static void walk(String operation, String prefix, Object node, Map<String, Object> root,
            Set<String> visiting, List<Field> fields) {
        if (node instanceof List<?> nodes) {
            nodes.forEach(each -> walk(operation, prefix, each, root, visiting, fields));
            return;
        }
        if (!(node instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> schema = map(node);
        if (schema.get("$ref") instanceof String ref) {
            if (visiting.add(ref)) {
                walk(operation, prefix, resolve(root, schema), root, visiting, fields);
                visiting.remove(ref);
            }
            return;
        }
        for (String combination : List.of("allOf", "oneOf", "anyOf", "prefixItems", "then", "else", "if")) {
            walk(operation, prefix, schema.get(combination), root, visiting, fields);
        }
        for (String nested : List.of("items", "additionalProperties", "unevaluatedProperties", "$defs")) {
            if (schema.get(nested) instanceof Map<?, ?> child) {
                walk(operation, prefix, "$defs".equals(nested) ? List.copyOf(map(child).values()) : child, root,
                        visiting, fields);
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<String, Object> property : map(properties).entrySet()) {
                String path = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
                Object value = property.getValue();
                Map<String, Object> resolved = value instanceof Map<?, ?> ? resolve(root, map(value)) : null;
                fields.add(new Field(operation, path, resolved));
                walk(operation, path, value, root, visiting, fields);
            }
        }
    }

    /** Follows a local {@code $ref} to its target; a node without one is returned as it is. */
    private static Map<String, Object> resolve(Map<String, Object> root, Map<String, Object> node) {
        Map<String, Object> current = node;
        for (int hops = 0; current.get("$ref") instanceof String ref && hops < 16; hops++) {
            if (!ref.startsWith("#/")) {
                throw new IllegalStateException("only local references are expected: " + ref);
            }
            Object target = root;
            for (String segment : ref.substring(2).split("/")) {
                target = map(target).get(segment.replace("~1", "/").replace("~0", "~"));
            }
            current = map(target);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("expected a mapping, found " + value);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static Map<String, Object> openApi() {
        String property = System.getProperty("nullnull.openapi.path");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("system property nullnull.openapi.path is required");
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(16 * 1024 * 1024);
            return map(new Yaml(new SafeConstructor(options)).load(Files.readString(Path.of(property),
                    StandardCharsets.UTF_8)));
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + property, unreadable);
        }
    }

    private static Path eventSchemaPath() {
        String property = System.getProperty("nullnull.events.schema.path");
        return Path.of(property == null || property.isBlank() ? "../../docs/contracts/events.schema.json" : property);
    }
}
