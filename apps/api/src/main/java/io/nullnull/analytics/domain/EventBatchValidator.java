package io.nullnull.analytics.domain;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Objects;

/**
 * Validates a batch against the canonical event schema - {@code docs/contracts/events.schema.json},
 * which AGENTS.md names as the event canon.
 *
 * <p>It validates against the document, not a Java transcription of it. The schema already carries
 * the closed per-event property allowlists, the route template allowlist and the product caps, and
 * every one of those is a rule a second implementation could get subtly wrong. PM-016 is what that
 * looks like when it happens: the route allowlist existed in two places and only one of them was an
 * allowlist, so {@code entryRoute} accepted a concrete path - with a trip id in it - for as long as
 * nobody compared the two.
 *
 * <p>What this does NOT do is decide anything about ownership. The schema has no owner or session
 * field to validate, because the contract has none: they are bound from the authenticated cookie
 * (invariant 11). A payload that invents one fails here as an unknown property.
 */
public final class EventBatchValidator {

    private final Schema schema;

    public EventBatchValidator(String schemaDocument) {
        Objects.requireNonNull(schemaDocument, "schemaDocument");
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaDocument, InputFormat.JSON);
    }

    /**
     * @return the schema errors, empty when the batch is acceptable. The caller decides what to do
     *     with them; nothing here formats a message, because a schema error can quote the value that
     *     failed and an analytics payload is exactly where a user's text would be.
     */
    public List<Error> validate(String batchDocument) {
        Objects.requireNonNull(batchDocument, "batchDocument");
        return schema.validate(batchDocument, InputFormat.JSON);
    }
}
