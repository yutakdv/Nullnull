package io.nullnull.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A real response checked against the schema the contract declares for its operation and status, with
 * the same 2020-12 evaluator openapiContractTest uses (JsonSchemaCheck, formats asserted).
 *
 * <p>JsonShape compares keys; this compares values. A key the fixture and the server both hold can still
 * carry a value the schema refuses: ImportDraftItem.originalLabel went out as null on every parse and
 * remap while the shape comparison passed, because the fixture had been written from that same response
 * (#16). The fixture ITs call this beside each shape comparison, so every response Frontend is told to
 * mock against is also valid against the contract.
 */
public final class ContractResponse {

    private static final JsonSchemaCheck CONTRACT = new JsonSchemaCheck(OpenApiDocument.load());

    private ContractResponse() {
    }

    public static void assertValid(String operationId, int status, JsonNode body) {
        assertValid(operationId, status, body.toString());
    }

    public static void assertValid(String operationId, int status, String body) {
        List<Error> errors = CONTRACT.validateResponse(operationId, String.valueOf(status), body);
        assertThat(errors).as("%s %s against docs/api/openapi.yaml", operationId, status).isEmpty();
    }
}
