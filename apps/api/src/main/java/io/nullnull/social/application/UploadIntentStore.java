package io.nullnull.social.application;

import io.nullnull.social.domain.UploadIntent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage for {@link UploadIntent} (V045's {@code upload_intents}). */
public interface UploadIntentStore {

    void insert(UploadIntent intent);

    /** The intent with this id, whoever owns it. Ownership is the caller's question to ask. */
    Optional<UploadIntent> find(UUID id);

    /**
     * Claims a PENDING intent for this caller, and answers whether THIS caller got it.
     *
     * <p>Conditional on the current status inside one statement rather than read-then-write: two
     * createPost calls carrying the same uploadId race here, and the loser must not publish a
     * second post from an object the winner has already deleted.
     *
     * @return true when this call claimed it, false when somebody else already had
     */
    boolean claim(UUID id, Instant at);

    /**
     * Records that a claimed intent produced no post because its bytes were refused.
     *
     * <p>REJECTED has a writer for a reason this repository keeps meeting from the other side: a
     * state nothing can produce is a value that looks like coverage and proves nothing. It is also
     * the only thing that tells an operator apart a visitor who published from one whose upload was
     * thrown away - both leave a non-PENDING row and no object.
     *
     * @return true when this call made the transition
     */
    boolean reject(UUID id, Instant at);
}
