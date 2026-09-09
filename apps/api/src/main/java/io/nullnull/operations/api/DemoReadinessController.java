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
 * <p>Session security is enforced by the operation interceptor (BA-010).
 */
@RestController
public class DemoReadinessController {

    private final DemoCapabilityQuery capabilities;

    public DemoReadinessController(DemoCapabilityQuery capabilities) {
        this.capabilities = capabilities;
    }

    @GetMapping("/demo/readiness")
    @io.nullnull.shared.http.NullnullOperation(id = "getDemoReadiness", security = io.nullnull.shared.http.NullnullOperation.Security.SESSION)
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
