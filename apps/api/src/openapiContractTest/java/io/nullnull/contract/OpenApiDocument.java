package io.nullnull.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads docs/api/openapi.yaml (path from system property {@code nullnull.openapi.path}) and
 * exposes operationIds and component schemas as JSON Schema 2020-12 wrapper documents.
 */
public final class OpenApiDocument {

    private static final Set<String> HTTP_METHODS = new java.util.HashSet<>(
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace"));

    private final Map<String, Object> document;
    private final JsonMapper json = JsonMapper.builder().build();

    private OpenApiDocument(Map<String, Object> document) {
        this.document = document;
    }

    @SuppressWarnings("unchecked")
    public static OpenApiDocument load() {
        String property = System.getProperty("nullnull.openapi.path");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("system property nullnull.openapi.path is required");
        }
        Path path = Path.of(property);
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(16 * 1024 * 1024);
            Object root = new Yaml(new SafeConstructor(options)).load(text);
            if (!(root instanceof Map<?, ?> map)) {
                throw new IllegalStateException("openapi.yaml root is not a mapping");
            }
            return new OpenApiDocument((Map<String, Object>) map);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read OpenAPI document " + path, exception);
        }
    }

    public String version() {
        return String.valueOf(section(document.get("info"), "info").get("version"));
    }

    @SuppressWarnings("unchecked")
    public TreeSet<String> operationIds() {
        TreeSet<String> ids = new TreeSet<>();
        Map<String, Object> paths = section(document.get("paths"), "paths");
        paths.forEach((path, item) -> {
            Map<String, Object> methods = section(item, "paths." + path);
            methods.forEach((method, operation) -> {
                if (HTTP_METHODS.contains(method) && operation instanceof Map<?, ?> op
                        && op.get("operationId") instanceof String id) {
                    ids.add(id);
                }
            });
        });
        return ids;
    }

    /** JSON Schema 2020-12 document whose root {@code $ref} points at one component schema. */
    public String schemaJson(String componentSchema) {
        Map<String, Object> components = section(document.get("components"), "components");
        Map<String, Object> schemas = section(components.get("schemas"), "components.schemas");
        if (!schemas.containsKey(componentSchema)) {
            throw new IllegalArgumentException("unknown component schema: " + componentSchema);
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        wrapper.put("$ref", "#/components/schemas/" + componentSchema);
        wrapper.put("components", Map.of("schemas", schemas));
        return json.writeValueAsString(wrapper);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("OpenAPI section missing or not a mapping: " + path);
        }
        return (Map<String, Object>) map;
    }
}
