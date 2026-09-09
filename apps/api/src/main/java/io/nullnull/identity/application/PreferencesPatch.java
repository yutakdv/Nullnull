package io.nullnull.identity.application;

import java.util.UUID;

/** Null fields mean absent except activeTripId, whose separate presence flag distinguishes clear. */
public record PreferencesPatch(String locale, String timezone, Boolean onboardingCompleted,
        boolean hasActiveTripId, UUID activeTripId) { }
