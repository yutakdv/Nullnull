package io.nullnull.identity.api;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.identity.application.PreferencesPatch;
import io.nullnull.shared.http.NullnullOperation;
import io.nullnull.shared.http.NullnullOperation.Security;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class OwnerController {
    private static final Set<String> FIELDS = Set.of("locale", "timezone", "onboardingCompleted", "activeTripId");
    private final OwnerPreferencesService preferences;
    public OwnerController(OwnerPreferencesService preferences) { this.preferences = preferences; }

    @GetMapping("/me")
    @NullnullOperation(id = "getCurrentOwner", security = Security.SESSION)
    public SessionController.OwnerProfile get(OwnerContext context) {
        return SessionController.OwnerProfile.from(preferences.get(context));
    }
    @PatchMapping(value = "/me", consumes = "application/merge-patch+json")
    @NullnullOperation(id = "updatePreferences", security = {Security.SESSION, Security.CSRF})
    public SessionController.OwnerProfile patch(OwnerContext context, @RequestBody JsonNode body) {
        if (!body.isObject() || body.isEmpty() || !FIELDS.containsAll(body.propertyNames())) { throw malformed(); }
        String locale = text(body, "locale");
        String timezone = text(body, "timezone");
        Boolean completed = null;
        if (body.has("onboardingCompleted")) {
            if (!body.get("onboardingCompleted").isBoolean()) { throw malformed(); }
            completed = body.get("onboardingCompleted").booleanValue();
        }
        UUID trip = null;
        if (body.has("activeTripId") && !body.get("activeTripId").isNull()) {
            String value = text(body, "activeTripId");
            // UUID.fromString alone accepts shortened groups that violate the OpenAPI uuid format.
            if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw malformed();
            }
            trip = UUID.fromString(value);
        }
        return SessionController.OwnerProfile.from(preferences.patch(context,
                new PreferencesPatch(locale, timezone, completed, body.has("activeTripId"), trip)));
    }
    private static String text(JsonNode body, String field) {
        if (!body.has(field)) { return null; }
        if (!body.get(field).isString()) { throw malformed(); }
        return body.get(field).asString();
    }
    private static ApiException malformed() {
        return new ApiException(ProblemCode.INVALID_REQUEST, "The preferences patch contains invalid fields.");
    }
}
