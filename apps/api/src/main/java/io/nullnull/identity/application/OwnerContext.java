package io.nullnull.identity.application;

import java.util.UUID;

/** Derived exclusively from the session cookie. Revoked access is limited to deletion replay. */
public record OwnerContext(UUID ownerId, UUID sessionId, boolean revoked) { }
