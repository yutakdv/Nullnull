package io.nullnull.identity.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.SessionProperties;
import io.nullnull.identity.application.SessionService;
import io.nullnull.identity.domain.Owner;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final SessionService sessions;
    private final SessionProperties properties;
    private final SessionHttpConfiguration http;
    private final io.nullnull.identity.application.DeletionService deletions;
    public SessionController(SessionService sessions, SessionProperties properties, SessionHttpConfiguration http,
            io.nullnull.identity.application.DeletionService deletions) {
        this.sessions = sessions; this.properties = properties; this.http = http; this.deletions = deletions;
    }
    @PostMapping("/demo/sessions")
    @NullnullOperation(id = "createDemoSession")
    public ResponseEntity<BootstrapResponse> bootstrap(HttpServletRequest request,
            @RequestBody(required = false) CreateRequest body) {
        var result = sessions.bootstrap(http.cookie(request), body == null ? null : body.locale(),
                body == null ? null : body.timezone());
        var response = ResponseEntity.status(result.cookie == null ? 200 : 201)
                .header("Cache-Control", "private, no-store");
        if (result.cookie != null) {
            response.header("Set-Cookie", ResponseCookie.from(properties.cookieName(), result.cookie)
                    .httpOnly(true).secure(properties.secure).sameSite("Lax").path("/").build().toString());
        }
        return response.body(new BootstrapResponse(OwnerProfile.from(result.owner), result.csrf.token, result.csrf.expiresAt));
    }
    @PostMapping("/session/csrf")
    @NullnullOperation(id = "issueCsrfToken", security = Security.SESSION)
    public CsrfResponse csrf(OwnerContext context) {
        var result = sessions.issueCsrf(context);
        return new CsrfResponse(result.token, result.expiresAt);
    }
    @DeleteMapping("/session")
    @NullnullOperation(id = "deleteCurrentSession", security = {Security.SESSION, Security.CSRF})
    public ResponseEntity<io.nullnull.identity.application.DeletionService.DeletionReceipt> delete(
            OwnerContext context, @RequestHeader("Idempotency-Key") String key) {
        var receipt = deletions.accept(context, key);
        return ResponseEntity.accepted().header("Location", receipt.statusUrl())
                .header("Cache-Control", "private, no-store").body(receipt);
    }
    @GetMapping("/deletion-requests/{deletionRequestId}")
    @NullnullOperation(id = "getDeletionRequest", security = Security.DELETION_STATUS_TOKEN)
    public ResponseEntity<io.nullnull.identity.application.DeletionService.DeletionStatus> deletionStatus(
            @PathVariable UUID deletionRequestId,
            @RequestHeader("X-Deletion-Status-Token") String token) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(deletions.status(deletionRequestId, token));
    }
    public record CreateRequest(String locale, String timezone) { }
    public record OwnerProfile(UUID id, String kind, String locale, String timezone,
            boolean onboardingCompleted, UUID activeTripId) {
        static OwnerProfile from(Owner owner) {
            return new OwnerProfile(owner.id(), owner.kind().name(), owner.locale(), owner.timezone(),
                    owner.onboardingCompleted(), owner.activeTripId());
        }
    }
    public record BootstrapResponse(OwnerProfile owner, String csrfToken, Instant expiresAt) {
        @Override public String toString() { return "BootstrapResponse[redacted]"; }
    }
    public record CsrfResponse(String csrfToken, Instant expiresAt) {
        @Override public String toString() { return "CsrfResponse[redacted]"; }
    }
}
