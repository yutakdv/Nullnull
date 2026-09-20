package io.nullnull.contract;

import java.util.Set;

/**
 * operationIds that the API currently serves. Every entry must exist in docs/api/openapi.yaml;
 * the contract suite fails on an invented endpoint. Entries are added slice by slice.
 */
public final class ImplementedOperationsRegistry {

    public static final Set<String> IMPLEMENTED =
            Set.of("getLiveness", "getReadiness", "getDemoReadiness", "createDemoSession", "issueCsrfToken",
                    "getCurrentOwner", "updatePreferences", "deleteCurrentSession", "getDeletionRequest",
                    "searchPlaces", "getPlace", "listRelatedPlaces", "getPlaceCrowdForecast",
                    "queryPlaceCrowdForecasts",
                    "listTrips", "createTrip", "getTrip", "updateTrip", "deleteTrip",
                    "listFeed", "getPost", "savePost", "unsavePost", "recordFeedFeedback",
                    "ingestEventBatch",
                    "listTripCandidates", "addTripCandidate", "removeTripCandidate", "getCandidateTripMatches",
                    "replaceTripInterests", "addTripItem", "removeTripItem", "reorderTripItems", "replaceTripItem", "updateTripItem",
                    "setTripItemConstraint", "removeTripItemConstraint",
                    "createOptimization", "getOptimization", "decideOptimization",
                    "revertOptimizationDecision", "listOptimizationHistory",
                    "parseTripImport", "remapTripImport", "confirmTripImport",
                    "previewTripDraft",
                    // BA-085. The three are served behind nullnull.notifications.enabled, which is
                    // OFF by default - serving an operation and enabling a feature are different
                    // things, and this registry is about the former.
                    "listNotifications", "markNotificationRead", "markAllNotificationsRead");

    private ImplementedOperationsRegistry() {
    }
}
