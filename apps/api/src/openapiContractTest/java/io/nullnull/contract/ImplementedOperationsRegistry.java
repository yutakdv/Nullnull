package io.nullnull.contract;

import java.util.Set;

/**
 * operationIds that the API currently serves. Every entry must exist in docs/api/openapi.yaml;
 * the contract suite fails on an invented endpoint. Entries are added slice by slice.
 */
public final class ImplementedOperationsRegistry {

    public static final Set<String> IMPLEMENTED =
            Set.of("getLiveness", "getReadiness", "getDemoReadiness");

    /**
     * Implemented operations whose declared security is NOT enforced yet.
     *
     * <p>{@code getDemoReadiness} declares {@code sessionCookie} in docs/api/openapi.yaml, and B01
     * ships no session layer at all - BA-010 brings it. Serving the route anyway is the deliberate
     * P0 choice (the contest build has to answer readiness from an anonymous window), but an
     * undeclared deviation is one nobody is reminded of: nothing would fail when the session layer
     * lands to say "this route still needs its cookie check wired".
     *
     * <p>So the deviation is listed here and asserted on. The assertion is written to go RED the
     * moment enforcement exists, not green: {@code SystemContractTest.declaredSecurityIsNotEnforcedYet}
     * requires the operation to still answer an unauthenticated call. When BA-010 makes it a 401, the
     * suite fails and the entry is removed in the same change.
     */
    public static final Set<String> SECURITY_NOT_YET_ENFORCED = Set.of("getDemoReadiness");

    private ImplementedOperationsRegistry() {
    }
}
