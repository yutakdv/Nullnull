package io.nullnull.analytics.application;

import com.networknt.schema.Error;
import io.nullnull.analytics.domain.EventBatchValidator;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ingestEventBatch.
 *
 * <p>Three things this deliberately is not. It is not a product command - nothing it stores is read
 * back into a trip, a candidate or a feed, so an analytics failure can never change what the user
 * sees. It is not a source of truth about what happened - a client event records what a screen
 * believed, and the server transaction is what happened. And it is not a place an identifier can
 * arrive: owner comes from the cookie, and the canonical schema refuses free text, coordinates and
 * concrete routes before anything is written.
 *
 * <p>The receipt separates {@code duplicates} from {@code accepted} because a retry after a timeout
 * is the normal case, not an error: the client cannot know whether the first attempt landed, and
 * telling it "0 accepted, 50 duplicates" is the honest answer rather than a second write or a 409.
 */
@Service
public class AnalyticsEventService {

    private final AnalyticsEventStore events;
    private final EventBatchValidator validator;
    private final JsonMapper json;
    private final Clock clock;

    public AnalyticsEventService(AnalyticsEventStore events, EventBatchValidator validator,
            JsonMapper json, Clock clock) {
        this.events = events;
        this.validator = validator;
        this.json = json;
        this.clock = clock;
    }

    public record Receipt(int accepted, int duplicates, int rejected) { }

    /**
     * @param batchDocument the raw request body. Raw on purpose: binding to a Java shape first would
     *     drop the unknown properties the schema is supposed to refuse, and "additionalProperties:
     *     false" is the check that keeps a user's text out of this table.
     */
    @Transactional
    public Receipt ingest(OwnerContext context, String batchDocument) {
        Objects.requireNonNull(context, "context");
        List<Error> errors = validator.validate(
                batchDocument == null ? "" : batchDocument);
        if (!errors.isEmpty()) {
            // The whole batch is refused rather than the offending events dropped. A batch that
            // half-lands leaves the client unable to say what to resend, and a schema violation
            // means the sender is not the client this contract describes - accepting its other
            // events would be trusting the same sender selectively.
            //
            // The detail says nothing about the values: a schema error quotes what failed, and an
            // analytics payload is exactly where a user's text would be.
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The event batch does not match the published event schema.");
        }
        JsonNode batch = json.readTree(batchDocument);
        Instant receivedAt = clock.instant();
        int accepted = 0;
        int duplicates = 0;
        for (JsonNode event : batch.get("events")) {
            boolean stored = events.store(new StoredEvent(
                    java.util.UUID.fromString(event.get("eventId").asString()),
                    context.ownerId(),
                    context.sessionId(),
                    event.get("name").asString(),
                    Instant.parse(event.get("occurredAt").asString()),
                    receivedAt,
                    event.get("context").get("route").asString(),
                    event.get("context").get("locale").asString(),
                    event.get("context").get("timezone").asString(),
                    appVersion(event.get("context")),
                    event.get("properties").toString()));
            if (stored) {
                accepted += 1;
            } else {
                duplicates += 1;
            }
        }
        // rejected is always 0 on a 202: a batch that had anything to reject was refused whole
        // above. The field stays in the contract because it is the receipt's shape, and a later
        // per-event policy would fill it without a contract change.
        return new Receipt(accepted, duplicates, 0);
    }

    private static String appVersion(JsonNode context) {
        JsonNode value = context.get("appVersion");
        return value == null || value.isNull() ? null : value.asString();
    }

    /** One row of {@code analytics_events}. */
    public record StoredEvent(java.util.UUID eventId, java.util.UUID ownerId,
            java.util.UUID sessionId, String name, Instant occurredAt, Instant receivedAt,
            String route, String locale, String timezone, String appVersion, String properties) { }
}
