package io.nullnull.contract;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;

/** Validates a JSON document against one OpenAPI component schema with a real 2020-12 evaluator. */
public final class JsonSchemaCheck {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final OpenApiDocument document;

    public JsonSchemaCheck(OpenApiDocument document) {
        this.document = document;
    }

    public List<Error> validate(String componentSchema, String json) {
        Schema schema = registry.getSchema(document.schemaJson(componentSchema), InputFormat.JSON);
        return schema.validate(json, InputFormat.JSON, context -> context
                .executionConfig(config -> config.formatAssertionsEnabled(true)));
    }
}
