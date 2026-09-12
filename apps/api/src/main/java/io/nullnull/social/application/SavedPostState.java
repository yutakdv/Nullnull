package io.nullnull.social.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The answer savePost gives.
 *
 * <p>{@code duplicate} distinguishes 201 from 200, and {@code savedAt} is the ORIGINAL timestamp in
 * both cases. Saving an already-saved post must not move it: the contract's two examples differ in
 * exactly one field for that reason, and a moved timestamp would reorder the owner's saved list on
 * a no-op.
 */
public record SavedPostState(UUID postId, boolean saved, Instant savedAt, boolean duplicate) {
    public SavedPostState {
        Objects.requireNonNull(postId, "postId");
        Objects.requireNonNull(savedAt, "savedAt");
    }
}
