package io.nullnull.testsupport;

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
        return validateAgainst(document.schemaJson(componentSchema), json);
    }

    /** Validates a body against what the contract declares for that operation and status. */
    public List<Error> validateResponse(String operationId, String statusCode, String json) {
        return validateAgainst(document.responseSchemaJson(operationId, statusCode), json);
    }

    private List<Error> validateAgainst(String schemaJson, String json) {
        Schema schema = registry.getSchema(schemaJson, InputFormat.JSON);
        return schema.validate(json, InputFormat.JSON, context -> context
                .executionConfig(config -> config.formatAssertionsEnabled(true)));
    }
}
