package io.nullnull.operations.api;

import io.nullnull.operations.application.DemoCapabilityQuery;
import io.nullnull.operations.application.DemoCapabilityQuery.DemoReadinessReport;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * operationId getDemoReadiness (docs/api/openapi.yaml, tag System, {@code FR-OPS-02}). The response
 * shape is the contract DTO {@code DemoReadiness}.
 *
 * <p>The capability list is a product vocabulary and never repeats an infrastructure probe name from
 * {@code /health/ready}; see {@link io.nullnull.operations.application.DemoCapabilities} for why the
 * two namespaces are kept apart.
 *
 * <p>The contract declares {@code sessionCookie} on this operation. There is no session or auth layer
 * in the service yet - BA-010 builds it - so nothing enforces that here, and the endpoint answers any
 * caller. It exposes only capability names and their status, which is the same class of information
 * {@code /health/ready} already publishes unauthenticated, but the slice that adds sessions has to put
 * this route behind them.
 *
 * <p>That is not left to a card to remember. The operation is listed in
 * {@code ImplementedOperationsRegistry.SECURITY_NOT_YET_ENFORCED} and
 * {@code SystemContractTest.declaredSecurityIsNotEnforcedYet} asserts BOTH halves of the deviation -
 * that the contract still declares a scheme, and that this route still answers an unauthenticated
 * call. The day a session layer makes it a 401, that test goes red and the entry comes out with it.
 */
@RestController
public class DemoReadinessController {

    private final DemoCapabilityQuery capabilities;

    public DemoReadinessController(DemoCapabilityQuery capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/demo/readiness")
    public DemoReadinessResponse demoReadiness() {
        DemoReadinessReport report = capabilities.readiness();
        List<CapabilityStatusResponse> statuses = report.capabilities().stream()
                .map(capability -> new CapabilityStatusResponse(capability.name(),
                        capability.status().name(), report.checkedAt(), capability.detail()))
                .toList();
        return new DemoReadinessResponse(report.overall().name(), statuses, report.checkedAt());
    }

    public record DemoReadinessResponse(String overall, List<CapabilityStatusResponse> capabilities,
            Instant checkedAt) {
    }
}
