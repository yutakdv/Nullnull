package io.nullnull.identity.domain;

/** docs/architecture/ERD.md §5 OwnerKind. Stored as text under a check constraint, never a native DB enum. */
public enum OwnerKind {
    ANONYMOUS,
    ACCOUNT
}
