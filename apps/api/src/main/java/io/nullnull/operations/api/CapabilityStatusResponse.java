package io.nullnull.operations.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The contract schema {@code CapabilityStatus} (docs/api/openapi.yaml). Shared by
 * {@code ReadinessStatus.checks} and {@code DemoReadiness.capabilities} because the contract points
 * both at this one schema - the two lists carry different vocabularies, not different shapes
 * ({@link io.nullnull.operations.application.DemoCapabilities}).
 *
 * <p>{@code checkedAt} is omitted when null, since the schema declares it as a date-time and not as a
 * nullable one; {@code detail} is declared nullable, so it is always written.
 */
public record CapabilityStatusResponse(String name, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant checkedAt, String detail) {
}
