package io.nullnull.contract;

import java.util.Set;

/**
 * operationIds that the API currently serves. Every entry must exist in docs/api/openapi.yaml;
 * the contract suite fails on an invented endpoint. Entries are added slice by slice.
 */
public final class ImplementedOperationsRegistry {

    public static final Set<String> IMPLEMENTED =
            Set.of("getLiveness", "getReadiness", "getDemoReadiness", "createDemoSession", "issueCsrfToken",
                    "getCurrentOwner", "updatePreferences", "deleteCurrentSession", "getDeletionRequest");

    private ImplementedOperationsRegistry() {
    }
}
