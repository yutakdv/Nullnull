package io.nullnull.operations.api;

import io.nullnull.operations.application.ReadinessQuery;
import io.nullnull.operations.application.ReadinessQuery.ReadinessReport;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * operationId getLiveness / getReadiness (docs/api/openapi.yaml, tag System). Response shapes
 * are the contract DTOs {@code HealthStatus}, {@code ReadinessStatus} and {@code CapabilityStatus}.
 *
 * <p>{@code checks} is the INFRASTRUCTURE namespace - database, jobs, recommendation - and is what
 * decides whether this task stays in the load balancer. Product capabilities are a separate list on
 * {@code getDemoReadiness}; see {@link io.nullnull.operations.application.DemoCapabilities}.
 */
@RestController
public class HealthController {

    private static final int NOT_READY_RETRY_AFTER_SECONDS = 5;

    private final ReadinessQuery readinessQuery;
    private final Clock clock;

    public HealthController(ReadinessQuery readinessQuery, Clock clock) {
        this.readinessQuery = readinessQuery;
        this.clock = clock;
    }

    @GetMapping("/health/live")
    @io.nullnull.shared.http.NullnullOperation(id = "getLiveness")
    public HealthStatusResponse liveness() {
        return new HealthStatusResponse("UP", clock.instant());
    }

    @GetMapping("/health/ready")
    @io.nullnull.shared.http.NullnullOperation(id = "getReadiness")
    public ReadinessStatusResponse readiness() {
        ReadinessReport report = readinessQuery.readiness();
        if (report.state() == ReadinessState.NOT_READY) {
            throw new ApiException(ProblemCode.SOURCE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "A required capability is not ready.", true, NOT_READY_RETRY_AFTER_SECONDS);
        }
        List<CapabilityStatusResponse> checks = report.checks().stream()
                .map(check -> new CapabilityStatusResponse(check.name(),
                        check.result().status().name(), check.result().checkedAt(),
                        check.result().detail()))
                .toList();
        return new ReadinessStatusResponse(report.state().name(), checks);
    }

    public record HealthStatusResponse(String status, Instant time) {
    }

    public record ReadinessStatusResponse(String status, List<CapabilityStatusResponse> checks) {
    }
}
