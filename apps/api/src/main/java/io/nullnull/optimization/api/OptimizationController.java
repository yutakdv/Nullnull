package io.nullnull.optimization.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.optimization.application.CreateOptimizationCommand;
import io.nullnull.optimization.application.DecideOptimizationCommand;
import io.nullnull.optimization.application.OptimizationHistoryPageView;
import io.nullnull.optimization.application.OptimizationHistoryQuery;
import io.nullnull.optimization.application.OptimizationService;
import io.nullnull.optimization.domain.OptimizationDecision;
import io.nullnull.optimization.domain.OptimizationDecisionKind;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** createOptimization and getOptimization. Nothing here can change a trip. */
@RestController
public class OptimizationController {

    private final OptimizationService optimizations;

    public OptimizationController(OptimizationService optimizations) {
        this.optimizations = optimizations;
    }

    @PostMapping(value = "/trips/{tripId}/optimizations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "createOptimization", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<OptimizationRunResponse> create(OwnerContext owner, @PathVariable UUID tripId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateOptimizationBody body) {
        OptimizationRun run = optimizations.create(owner, tripId, ifMatch, idempotencyKey,
                command(body));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/optimizations/" + run.id())
                .header("Cache-Control", "private, no-store")
                .body(OptimizationRunResponse.from(run));
    }

    @GetMapping(value = "/optimizations/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "getOptimization", security = Security.SESSION)
    public ResponseEntity<OptimizationRunResponse> get(OwnerContext owner, @PathVariable UUID runId) {
        OptimizationRun run = optimizations.get(owner, runId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header("Cache-Control", "private, no-store");
        // Present for QUEUED and RUNNING only, which is what the contract says and also the only
        // state in which asking again could produce a different answer.
        OptimizationService.retryAfter(run)
                .ifPresent(seconds -> response.header("Retry-After", Integer.toString(seconds)));
        return response.body(OptimizationRunResponse.from(run));
    }

    @PostMapping(value = "/optimizations/{runId}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "decideOptimization", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<OptimizationDecisionResponse> decide(OwnerContext owner,
            @PathVariable UUID runId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody OptimizationDecisionBody body) {
        OptimizationDecision decision = optimizations.decide(owner, runId, ifMatch, idempotencyKey,
                decision(body));
        // The trip version the caller now holds. An APPLY moved it; a KEEP did not, so the tag is the
        // version the decision was made against - which is what effectiveTripVersion() answers, and
        // why that method exists rather than the caller branching here.
        return ResponseEntity.ok()
                .eTag("\"" + decision.effectiveTripVersion() + "\"")
                .header("Cache-Control", "private, no-store")
                .body(OptimizationDecisionResponse.from(decision));
    }

    @PostMapping(value = "/optimization-decisions/{decisionId}/revert",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "revertOptimizationDecision", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<RevertDecisionResponse> revert(OwnerContext owner,
            @PathVariable UUID decisionId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        OptimizationDecision decision = optimizations.revert(owner, decisionId, ifMatch, idempotencyKey);
        // A REVERT always moves the trip, so the tag is the version it produced. Unlike decide, there
        // is no KEEP branch here and effectiveTripVersion() would answer the same thing either way -
        // it is used anyway so the two routes cannot drift into meaning different things by the tag.
        return ResponseEntity.ok()
                .eTag("\"" + decision.effectiveTripVersion() + "\"")
                .header("Cache-Control", "private, no-store")
                .body(RevertDecisionResponse.from(decision));
    }

    @GetMapping(value = "/optimizations", produces = MediaType.APPLICATION_JSON_VALUE)
    @NullnullOperation(id = "listOptimizationHistory", security = Security.SESSION)
    public ResponseEntity<OptimizationHistoryPageResponse> history(OwnerContext owner,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        OptimizationHistoryPageView page = optimizations.history(owner, tripId, cursor, limit);
        return ResponseEntity.ok()
                // Owner-scoped, so never cacheable by anything between here and the browser.
                .header("Cache-Control", "private, no-store")
                .body(new OptimizationHistoryPageResponse(
                        page.items().stream().map(OptimizationHistoryItemResponse::from).toList(),
                        new CursorPageResponse(page.nextCursor(), page.hasMore())));
    }

    private static DecideOptimizationCommand decision(OptimizationDecisionBody body) {
        if (body == null) {
            throw new TripValidationException("decision", "NotNull", "a request body is required");
        }
        return new DecideOptimizationCommand(body.proposalId(), kind(body.decision()));
    }

    private static OptimizationDecisionKind kind(String value) {
        if (value == null) {
            throw new TripValidationException("decision", "NotNull", "decision is required");
        }
        try {
            return OptimizationDecisionKind.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            // The discriminator, so an unknown value is a body that matches no variant at all.
            throw new TripValidationException("decision", "Unsupported", "decision must be APPLY or KEEP");
        }
    }

    private static CreateOptimizationCommand command(CreateOptimizationBody body) {
        if (body == null) {
            throw new TripValidationException("scope", "NotNull", "a request body is required");
        }
        return new CreateOptimizationCommand(scope(body.scope()), body.targetItemId(),
                targetDate(body.targetDate()), body.inputTripVersion() == null ? 0
                        : body.inputTripVersion(),
                Boolean.TRUE.equals(body.includeCandidates()), body.objective());
    }

    private static OptimizationScope scope(String value) {
        try {
            return OptimizationScope.of(value);
        } catch (IllegalArgumentException unknown) {
            // The discriminator, so an unknown value is a body that matches no variant at all.
            throw new TripValidationException("scope", "Unsupported", "scope must be ITEM, DAY or TRIP");
        }
    }

    private static LocalDate targetDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            throw new TripValidationException("targetDate", "Format", "targetDate must be a date");
        }
    }

    /** The union of the three variants; which fields are allowed is CreateOptimizationCommand's rule. */
    public record CreateOptimizationBody(String scope, UUID targetItemId, String targetDate,
            Long inputTripVersion, Boolean includeCandidates, String objective) {
    }

    /**
     * OptimizationRun as the contract spells it.
     *
     * <p>{@code proposals} and {@code decisions} are empty arrays rather than absent: the contract
     * requires both fields, and a run that has not been answered has none of either. They are filled
     * by the slices that produce them (BA-051, BA-052).
     *
     * <p>There is deliberately no {@code targetItemId} here. The run stores one, but
     * {@code OptimizationRun} in docs/api/openapi.yaml has no such property - PM-015 names that as an
     * open question, and answering it by adding a field would settle an FE-facing boundary that is
     * still being reviewed.
     */
    public record OptimizationRunResponse(UUID id, UUID tripId, String scope, String status,
            long inputTripVersion, UUID inputRevisionId, boolean includeCandidates, String dataFingerprint,
            String algorithmVersion, Instant queuedAt, Instant completedAt, Instant expiresAt,
            List<Object> proposals, List<UUID> snapshotSetIds, List<Object> decisions,
            OptimizationFailureResponse failure) {

        static OptimizationRunResponse from(OptimizationRun run) {
            return new OptimizationRunResponse(run.id(), run.tripId(), run.scope().name(),
                    run.status().name(), run.inputTripVersion(), run.inputRevisionId(),
                    run.includeCandidates(), run.dataFingerprint(), run.algorithmVersion(),
                    run.queuedAt(), run.completedAt(), run.expiresAt(), List.of(), run.snapshotSetIds(),
                    List.of(), OptimizationFailureResponse.from(run));
        }
    }

    public record OptimizationDecisionBody(UUID proposalId, String decision) {
    }

    /**
     * The contract's {@code InitialOptimizationDecision}: a discriminated union, not one record with
     * nullable halves.
     *
     * <p>Both variants are {@code additionalProperties: false}, so a single record carrying null
     * apply fields would serialise them and answer a shape the schema refuses. Keeping them apart is
     * also what lets a client branch on {@code decision} without reading four more fields to find out
     * whether they mean anything.
     */
    public sealed interface OptimizationDecisionResponse {

        static OptimizationDecisionResponse from(OptimizationDecision decision) {
            if (decision.decision() == OptimizationDecisionKind.APPLY) {
                return new ApplyDecisionResponse(decision.id(), decision.runId(), decision.proposalId(),
                        decision.decision().name(), decision.resultingTripVersion(),
                        decision.beforeRevisionId(), decision.afterRevisionId(), decision.revertUntil(),
                        decision.decidedAt());
            }
            return new KeepDecisionResponse(decision.id(), decision.runId(), decision.proposalId(),
                    decision.decision().name(), decision.decidedAt());
        }
    }

    public record ApplyDecisionResponse(UUID id, UUID runId, UUID proposalId, String decision,
            Long resultingTripVersion, UUID beforeRevisionId, UUID afterRevisionId, Instant revertUntil,
            Instant decidedAt) implements OptimizationDecisionResponse {
    }

    public record KeepDecisionResponse(UUID id, UUID runId, UUID proposalId, String decision,
            Instant decidedAt) implements OptimizationDecisionResponse {
    }

    /**
     * The contract's {@code RevertOptimizationDecision}, which is its own schema rather than a third
     * variant of {@code InitialOptimizationDecision}.
     *
     * <p>Two fields are why they cannot be one type: a REVERT carries {@code revertedDecisionId},
     * which the initial variants forbid, and it omits {@code revertUntil}, which is how the contract
     * says a REVERT cannot itself be reverted (V030's column comment names that as its source). Both
     * schemas are {@code additionalProperties: false}, so a shared record would answer a shape the
     * schema refuses in whichever direction it was merged.
     */
    public record RevertDecisionResponse(UUID id, UUID runId, UUID proposalId, String decision,
            Long resultingTripVersion, UUID beforeRevisionId, UUID afterRevisionId,
            UUID revertedDecisionId, Instant decidedAt) {

        static RevertDecisionResponse from(OptimizationDecision decision) {
            return new RevertDecisionResponse(decision.id(), decision.runId(), decision.proposalId(),
                    decision.decision().name(), decision.resultingTripVersion(),
                    decision.beforeRevisionId(), decision.afterRevisionId(),
                    decision.revertedDecisionId(), decision.decidedAt());
        }
    }

    public record OptimizationHistoryPageResponse(List<OptimizationHistoryItemResponse> items,
            CursorPageResponse page) {
    }

    public record CursorPageResponse(String nextCursor, boolean hasMore) {
    }

    /**
     * One history line: state, time, and where to go. No itinerary content, which the operation's
     * description states as a rule rather than a consequence ("This view never returns or causes a
     * duplicate itinerary snapshot").
     */
    public record OptimizationHistoryItemResponse(UUID runId, UUID tripId, String tripTitle,
            String scope, String status, String decision, String runLink, Instant queuedAt,
            Instant decidedAt) {

        static OptimizationHistoryItemResponse from(OptimizationHistoryQuery.HistoryRow row) {
            return new OptimizationHistoryItemResponse(row.runId(), row.tripId(), row.tripTitle(),
                    row.scope().name(), row.status().name(),
                    row.decision() == null ? null : row.decision().name(),
                    // A client router path, not an API path: the contract's pattern excludes the
                    // /api/v1 base on purpose and says callers may navigate to it verbatim.
                    "/trip/" + row.tripId() + "/optimizations/" + row.runId(),
                    row.queuedAt(), row.decidedAt());
        }
    }

    public record OptimizationFailureResponse(String code, String message, boolean retryable) {
        static OptimizationFailureResponse from(OptimizationRun run) {
            if (run.failureCode() == null) {
                return null;
            }
            return new OptimizationFailureResponse(run.failureCode().name(), run.failureMessage(),
                    run.failureCode().retryable());
        }
    }
}
