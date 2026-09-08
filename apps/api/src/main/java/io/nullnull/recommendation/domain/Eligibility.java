package io.nullnull.recommendation.domain;

import java.util.List;
import java.util.Objects;

/**
 * Immutable eligibility decision for one candidate. INELIGIBLE and UNKNOWN always carry at
 * least one reason so that rejection counts can be observed without exposing inputs.
 */
public record Eligibility(EligibilityState state, List<Reason> reasons) {

    public Eligibility {
        Objects.requireNonNull(state, "state");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (state != EligibilityState.ELIGIBLE && reasons.isEmpty()) {
            throw new IllegalArgumentException(state + " requires at least one reason");
        }
    }

    public static Eligibility eligible() {
        return new Eligibility(EligibilityState.ELIGIBLE, List.of());
    }

    public static Eligibility ineligible(Reason first, Reason... more) {
        return new Eligibility(EligibilityState.INELIGIBLE, concat(first, more));
    }

    public static Eligibility unknown(Reason first, Reason... more) {
        return new Eligibility(EligibilityState.UNKNOWN, concat(first, more));
    }

    public boolean isEligible() {
        return state == EligibilityState.ELIGIBLE;
    }

    /**
     * Combines two independent checks: any INELIGIBLE wins, otherwise any UNKNOWN wins,
     * otherwise ELIGIBLE. Reasons are preserved in evaluation order.
     */
    public Eligibility and(Eligibility other) {
        Objects.requireNonNull(other, "other");
        EligibilityState combined;
        if (state == EligibilityState.INELIGIBLE || other.state == EligibilityState.INELIGIBLE) {
            combined = EligibilityState.INELIGIBLE;
        } else if (state == EligibilityState.UNKNOWN || other.state == EligibilityState.UNKNOWN) {
            combined = EligibilityState.UNKNOWN;
        } else {
            combined = EligibilityState.ELIGIBLE;
        }
        List<Reason> all = new java.util.ArrayList<>(reasons);
        all.addAll(other.reasons);
        return new Eligibility(combined, all);
    }

    private static List<Reason> concat(Reason first, Reason[] more) {
        List<Reason> all = new java.util.ArrayList<>(1 + more.length);
        all.add(Objects.requireNonNull(first, "first"));
        for (Reason reason : more) {
            all.add(Objects.requireNonNull(reason, "reason"));
        }
        return all;
    }
}
