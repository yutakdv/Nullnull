package io.nullnull.crowd.domain;

public enum ApprovalState {
    DEV_APPROVED,
    PROD_PENDING,
    PROD_APPROVED,
    DISABLED;

    /** Contest production may use the approved 1,000/day development key. */
    public boolean permitsCollection() {
        return this == DEV_APPROVED || this == PROD_APPROVED;
    }
}
