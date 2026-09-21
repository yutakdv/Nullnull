package io.nullnull.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The shape of a JSON document: every key path it holds, at every level.
 *
 * <p>For comparing a contract fixture with a real response, where the ids, times and names differ by
 * construction and the question is only whether the two have the same keys everywhere. Array elements
 * share one {@code []} segment, so the comparison is of what an element looks like rather than of how
 * many there are - and a null is a leaf, so a key the fixture fills and the server leaves null shows up
 * as the children only one side has.
 */
public final class JsonShape {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonShape() {
    }

    public static SortedSet<String> of(JsonNode node) {
        SortedSet<String> paths = new TreeSet<>();
        walk(node, "$", paths, null);
        return paths;
    }

    /** Compare older pinned examples while BA-086's optional text credit is tested separately. */
    public static SortedSet<String> withoutField(JsonNode node, String omittedField) {
        SortedSet<String> paths = new TreeSet<>();
        walk(node, "$", paths, omittedField);
        return paths;
    }

    /** A fixture under packages/contracts/fixtures, read from the repository the suite runs in. */
    public static JsonNode fixture(String relative) {
        try {
            return JSON.readTree(Files.readString(Path.of("../../packages/contracts/fixtures/" + relative)));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static void walk(JsonNode node, String path, SortedSet<String> paths, String omittedField) {
        if (node.isObject()) {
            node.properties().forEach(property -> {
                if (property.getKey().equals(omittedField)) {
                    return;
                }
                String child = path + "." + property.getKey();
                paths.add(child);
                walk(property.getValue(), child, paths, omittedField);
            });
        } else if (node.isArray()) {
            node.forEach(element -> walk(element, path + "[]", paths, omittedField));
        }
    }
}
