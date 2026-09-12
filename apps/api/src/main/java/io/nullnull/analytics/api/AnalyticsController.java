package io.nullnull.analytics.api;

import io.nullnull.analytics.application.AnalyticsEventService;
import io.nullnull.analytics.application.AnalyticsEventService.Receipt;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ingestEventBatch.
 *
 * <p>The body arrives as a {@code String} rather than a bound object on purpose. Binding first would
 * drop whatever the Java shape does not declare, and {@code additionalProperties: false} in the
 * canonical schema is precisely the check that keeps an unknown field - a pasted itinerary, a
 * coordinate, a forged owner id - out of this table. What is validated has to be what was sent.
 */
@RestController
public class AnalyticsController {

    private final AnalyticsEventService analytics;

    public AnalyticsController(AnalyticsEventService analytics) {
        this.analytics = analytics;
    }

    @PostMapping(value = "/events/batch", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "ingestEventBatch", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<EventBatchReceiptResponse> ingest(OwnerContext owner,
            @RequestBody(required = false) String batch) {
        Receipt receipt = analytics.ingest(owner, batch);
        // 202, not 201: nothing here is a resource the caller can go and read, and the events are
        // accepted for measurement rather than acted on.
        return ResponseEntity.accepted()
                .header("Cache-Control", "private, no-store")
                .body(new EventBatchReceiptResponse(receipt.accepted(), receipt.duplicates(),
                        receipt.rejected()));
    }

    /** {@code EventBatchReceipt}. */
    public record EventBatchReceiptResponse(int accepted, int duplicates, int rejected) { }
}
