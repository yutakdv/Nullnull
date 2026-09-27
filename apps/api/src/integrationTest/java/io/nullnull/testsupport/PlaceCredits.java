package io.nullnull.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The credits the places of a JSON document carry, by value.
 *
 * <p>{@link JsonShape} compares keys, so a fixture that credits KTO and a response that credits nothing at the
 * same depth, or credits it in other words or another locale tag, can share one shape - and a comparison of
 * top-level key names cannot even see the depth. A screen draws both credits verbatim, so the fixture Frontend
 * mocks against has to hold the values the server sends, not only its keys (#387). Like {@code JsonShape}, this
 * merges array elements: it is the set of distinct values, not one per place.
 */
public final class PlaceCredits {

    private PlaceCredits() {
    }

    /** Both credits of the places in {@code actual}, by value, against the fixture Frontend mocks with. */
    public static void assertSameAs(JsonNode actual, JsonNode fixture, String label) {
        for (String credit : List.of("sourceAttribution", "textProvenance")) {
            assertThat(of(actual, credit)).as("%s %s", label, credit).isEqualTo(of(fixture, credit));
        }
    }

    /** The distinct values of {@code field} on every place in the document: an object with PlaceSummary's required keys. */
    public static Set<JsonNode> of(JsonNode document, String field) {
        Set<JsonNode> values = new LinkedHashSet<>();
        collect(document, field, values);
        return values;
    }

    private static void collect(JsonNode node, String field, Set<JsonNode> values) {
        if (node.isObject()) {
            if (node.has("id") && node.has("name") && node.has("categoryCode") && node.has("regionCode")) {
                values.add(node.get(field));
            }
            node.properties().forEach(property -> collect(property.getValue(), field, values));
        } else if (node.isArray()) {
            node.forEach(element -> collect(element, field, values));
        }
    }
}
