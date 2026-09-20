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
                    // BA-082 post authoring. The pair is one flow: the ticket is signed here
                    // and spent by createPost, which is why neither is useful alone.
                    "createPostImageUpload", "createPost",
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
                    "listNotifications", "markNotificationRead", "markAllNotificationsRead",
                    // BA-091. The Live tab's three operations, all served behind
                    // nullnull.capabilities.live, which is OFF by default. The same distinction the
                    // notifications entry above draws - serving an operation and enabling a feature
                    // are different things, and this registry is the former.
                    //
                    // THE FLAG CAN NOW BE TURNED ON, which this comment used to deny. It said
                    // DemoCapabilityQuery refuses to start while nothing stores a reading; BA-090
                    // ended that - SEOUL_CITYDATA is promoted in V046, a collector stores a reading
                    // per area, and `live` left WITHOUT_A_SOURCE. Turning it on is a deployment
                    // decision, the same as optimization's.
                    "queryLiveAreas", "listLiveAreaPlaces", "getLivePlace");

    private ImplementedOperationsRegistry() {
    }
}
