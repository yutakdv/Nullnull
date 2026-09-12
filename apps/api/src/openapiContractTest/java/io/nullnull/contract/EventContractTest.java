package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * The route allowlist and the event vocabulary exist in two documents. This keeps them one rule.
 *
 * <p>docs/api/openapi.yaml declares {@code ClientEvent} for the HTTP shape and
 * docs/contracts/events.schema.json is the event canon that the server actually validates against.
 * Both spell out the route templates, and both spell out the event names. PM-016 is what a split
 * like that costs: the route allowlist was already duplicated INSIDE the event schema, one copy was
 * a free string, and {@code entryRoute} accepted a concrete path - with a trip id in it - for as
 * long as nothing compared the two. Merging those two was not enough while a third copy sits in the
 * OpenAPI document.
 */
class EventContractTest {

    private static final Pattern YAML_ENUM_ENTRY = Pattern.compile("^\\s+- (\\S+)\\s*$");

    private static String read(String property, String fallback) {
        String path = System.getProperty(property);
        Path file = path == null || path.isBlank() ? Path.of(fallback) : Path.of(path);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException("cannot read " + file, missing);
        }
    }

    private static String openapi() {
        return read("nullnull.openapi.path", "../../docs/api/openapi.yaml");
    }

    private static String eventSchema() {
        return read("nullnull.events.schema.path", "../../docs/contracts/events.schema.json");
    }

    /** The entries of the YAML enum that follows {@code marker}. */
    private static List<String> yamlEnumAfter(String document, String marker) {
        int start = document.indexOf(marker);
        assertThat(start).as("marker present: " + marker).isNotEqualTo(-1);
        List<String> values = new ArrayList<>();
        boolean inEnum = false;
        for (String line : document.substring(start).lines().toList()) {
            if (line.trim().equals("enum:")) {
                inEnum = true;
                continue;
            }
            if (!inEnum) {
                continue;
            }
            Matcher entry = YAML_ENUM_ENTRY.matcher(line);
            if (!entry.matches()) {
                break;
            }
            values.add(entry.group(1));
        }
        return values;
    }

    /** The entries of the JSON string array named {@code key} nearest after {@code marker}. */
    private static List<String> jsonArrayAfter(String document, String marker, String key) {
        int start = document.indexOf(marker);
        assertThat(start).as("marker present: " + marker).isNotEqualTo(-1);
        int arrayStart = document.indexOf("\"" + key + "\"", start);
        assertThat(arrayStart).as(key + " present after " + marker).isNotEqualTo(-1);
        int open = document.indexOf('[', arrayStart);
        int close = document.indexOf(']', open);
        List<String> values = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(document.substring(open, close));
        while (quoted.find()) {
            values.add(quoted.group(1));
        }
        return values;
    }

    @Test
    @DisplayName("BA-033-T1 the route allowlist is the same list in the contract and the event canon")
    void theRouteAllowlistIsOneList() {
        List<String> contractRoutes = yamlEnumAfter(openapi(), "            route:");
        List<String> canonRoutes = jsonArrayAfter(eventSchema(), "\"routeTemplate\"", "enum");
        // Non-empty, so a parser that silently matched nothing cannot make this pass.
        assertThat(contractRoutes).hasSize(15);
        assertThat(canonRoutes).containsExactlyElementsOf(contractRoutes);
    }

    @Test
    @DisplayName("BA-033-T1 every declared event name has a property allowlist in the canon")
    void everyEventNameIsBranchedInTheCanon() {
        List<String> contractNames = yamlEnumAfter(openapi(), "    ClientEvent:");
        assertThat(contractNames).hasSize(17);

        JsonNode canon = JsonMapper.builder().build().readTree(eventSchema());
        JsonNode event = canon.get("$defs").get("event");
        JsonNode base = canon.get("$defs").get("baseEvent");

        // Why this matters more than tidiness: baseEvent declares `properties` as a bare
        // {"type": "object"}, so an event name with no if/then branch is validated by nothing. Its
        // payload would accept free text, a coordinate, anything - through the endpoint whose whole
        // purpose is refusing those. A name added to the contract and to the canon's name enum but
        // not to a branch is an open door nothing else would notice.
        JsonNode openProperties = base.get("properties").get("properties");
        assertThat(openProperties.properties()).as("baseEvent constrains nothing by itself")
                .hasSize(1);
        assertThat(openProperties.get("type").asString()).isEqualTo("object");

        List<String> branched = new ArrayList<>();
        for (JsonNode branch : event.get("allOf")) {
            JsonNode name = branch.path("if").path("properties").path("name");
            if (name.has("const")) {
                branched.add(name.get("const").asString());
            }
            for (JsonNode value : name.path("enum")) {
                branched.add(value.asString());
            }
        }
        assertThat(branched).as("the walk found branches at all").isNotEmpty();
        assertThat(branched).as("every contract event name has a property allowlist")
                .containsAll(contractNames);
        assertThat(contractNames).as("the canon branches no event the contract does not declare")
                .containsAll(branched);

        // And the canon's own name enum is the same seventeen, so a name cannot be accepted at the
        // envelope while having no branch below it.
        List<String> canonNames = new ArrayList<>();
        for (JsonNode value : base.get("properties").get("name").get("enum")) {
            canonNames.add(value.asString());
        }
        assertThat(canonNames).containsExactlyElementsOf(contractNames);
    }
}
