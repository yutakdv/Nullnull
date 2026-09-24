package io.nullnull.social.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.social.application.ImageSanitiser.ImageRejectedException;
import io.nullnull.social.application.ImageSanitiser.SanitisedImage;
import io.nullnull.social.domain.UploadIntent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a claimed upload and some text into a published post, in one call.
 *
 * <p>WHY THIS IS SYNCHRONOUS. PostDetail requires coverUrl, body and places, so a post that exists
 * before its cover does would need a state the contract cannot express - and expressing it would
 * cost a PENDING post status, a rule for whether the feed shows one, an orphan when the second half
 * never arrives, and polling in the client. This call does the work and returns the finished post.
 * The price is latency proportional to the image, which is why the size ceiling is small.
 *
 * <p>ORDER, AND WHY IT IS THIS ORDER. The intent is claimed first, in one conditional statement, so
 * two calls carrying the same uploadId cannot both reach the object store - the loser stops before
 * touching anything, which matters because the winner deletes the original. Reading, sanitising,
 * publishing and deleting are object-store calls and happen OUTSIDE any database transaction, which
 * this repository requires of every external call. Only the three rows are transactional.
 *
 * <p>WHAT CAN STILL GO WRONG, NAMED RATHER THAN HANDLED. If the transaction fails after the
 * sanitised copy is published, the published object outlives the post that would have pointed at
 * it. Nothing serves it - no post carries the URL, and the URL is not guessable - so it is litter,
 * not exposure. The opposite order would be worse: a post row whose cover has not been written is a
 * broken card in the feed.
 */
@Service
public class PostAuthoringService {

    private static final String CREATE_ROUTE = "POST /posts";
    // Lease for the bounded-size image's S3 read, publish and delete outside the command transaction.
    private static final Duration PUBLISH_BOUND = Duration.ofMinutes(2);

    private final UploadIntentStore intents;
    private final ObjectStorage storage;
    private final ImageSanitiser sanitiser;
    private final FeedStore feed;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public PostAuthoringService(UploadIntentStore intents, ObjectStorage storage,
            ImageSanitiser sanitiser, FeedStore feed, IdempotencyGuard idempotency,
            ObjectMapper json,
            Clock clock) {
        this.intents = intents;
        this.storage = storage;
        this.sanitiser = sanitiser;
        this.feed = feed;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param ownerId derived from the session, never from the request (invariant 11)
     * @throws AuthoringRejectedException for every refusal, with the reason the API layer maps
     */
    public UUID publish(UUID ownerId, String idempotencyKey, UUID uploadId, String title,
            String body, String altText, List<UUID> placeIds) {
        String fingerprint = RequestFingerprint.of("createPost", Map.of(),
                json.writeValueAsString(new PublishPayload(uploadId, title, body, altText, placeIds)))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(ownerId, CREATE_ROUTE,
                idempotencyKey, fingerprint,
                new IdempotencyGuard.Prelude<>(PUBLISH_BOUND, () -> prepare(ownerId, uploadId)),
                prepared -> new IdempotencyGuard.CommandOutcome<>(201,
                        new PublishedPost(writeRows(ownerId, uploadId, title, body, altText,
                                placeIds, prepared.servedUrl(), prepared.checksum(), prepared.now()))),
                value -> value);
        return json.readValue(guarded.body(), PublishedPost.class).postId();
    }

    private PreparedPost prepare(UUID ownerId, UUID uploadId) {
        Instant now = clock.instant();
        Optional<UploadIntent> found = intents.find(uploadId);

        // An intent belonging to somebody else is answered exactly as one that never existed. A
        // distinct refusal would confirm that the id is real, which is the thing BA-070-T1 says a
        // rejection must not do - the standard is indistinguishable, not merely refused.
        if (found.isEmpty() || !found.get().belongsTo(ownerId)) {
            throw new AuthoringRejectedException(AuthoringRejection.UPLOAD_UNUSABLE);
        }
        UploadIntent intent = found.get();
        if (!intent.usableAt(now)) {
            throw new AuthoringRejectedException(AuthoringRejection.UPLOAD_UNUSABLE);
        }
        if (!intents.claim(uploadId, now)) {
            throw new AuthoringRejectedException(AuthoringRejection.UPLOAD_UNUSABLE);
        }

        SanitisedImage sanitised;
        byte[] original;
        try {
            original = storage.readQuarantined(intent.quarantineKey());
        } catch (ObjectStorage.ObjectNotFoundException e) {
            // Signed but never uploaded. The intent is spent either way: its key is gone.
            intents.reject(uploadId, now);
            throw new AuthoringRejectedException(AuthoringRejection.UPLOAD_MISSING);
        }
        if (original.length != intent.contentLength()) {
            // The signed PUT declares an exact length. Check it again on the received bytes so a
            // shorter object cannot be published if storage accepts a mismatched declaration.
            cleanUp(intent, uploadId, now);
            throw new AuthoringRejectedException(AuthoringRejection.IMAGE_REJECTED);
        }
        if (!sha256(original).equals(intent.checksumSha256())) {
            // The presigned PUT binds type and length, but S3 does not compare the checksum in
            // this signature. Refuse changed bytes before decoding or publishing them.
            cleanUp(intent, uploadId, now);
            throw new AuthoringRejectedException(AuthoringRejection.IMAGE_REJECTED);
        }
        try {
            sanitised = sanitiser.sanitise(intent.contentType(), original);
        } catch (ImageRejectedException e) {
            cleanUp(intent, uploadId, now);
            throw new AuthoringRejectedException(AuthoringRejection.IMAGE_REJECTED);
        }

        String servedUrl = storage.publish(publishedKey(uploadId, sanitised),
                sanitised.bytes(), sanitised.format().mediaType());
        // The original is deleted because it is the copy that still carries the camera's
        // coordinates. Keeping it would be storing precise location under another name (invariant
        // 10), and no part of this flow reads it again.
        storage.deleteQuarantined(intent.quarantineKey());

        return new PreparedPost(servedUrl, sha256(sanitised.bytes()), now);
    }

    /**
     * The three rows, in one transaction: a post whose cover asset is missing is a broken card, and
     * an asset no post points at is a row nothing can reach.
     *
     * <p>The idempotency guard owns this transaction and records the response with these rows.
     * A replay never claims the ticket or calls the object store again.
     */
    private UUID writeRows(UUID ownerId, UUID uploadId, String title, String body, String altText,
            List<UUID> placeIds, String servedUrl, String checksum, Instant now) {
        UUID assetId = UUID.randomUUID();
        feed.insertUserUploadCover(assetId, servedUrl, uploadId.toString(), altText, checksum, now);
        UUID postId = UUID.randomUUID();
        feed.insertAuthoredDraftPost(postId, ownerId, title, body, servedUrl, assetId, now);
        for (int position = 0; position < placeIds.size(); position++) {
            // Position 0 is the card's headline place, which is what PRIMARY means here; the rest
            // are SECONDARY. Post.primaryPlaceId() reads position 0 back.
            feed.linkPostPlace(postId, placeIds.get(position), position,
                    position == 0 ? "PRIMARY" : "SECONDARY");
        }
        // A-058: published on validation, with no approval step in between.
        feed.publishPost(postId, now, now);
        return postId;
    }

    private void cleanUp(UploadIntent intent, UUID uploadId, Instant now) {
        // Deleted where it is refused rather than left to the lifecycle rule, whose granularity is
        // a day: bytes we have already decided not to publish should not sit in the bucket for one.
        storage.deleteQuarantined(intent.quarantineKey());
        intents.reject(uploadId, now);
    }

    private static String publishedKey(UUID uploadId, SanitisedImage image) {
        return "covers/user/" + uploadId + "." + image.format().extension();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private record PublishPayload(UUID uploadId, String title, String body, String altText,
            List<UUID> placeIds) {}

    private record PreparedPost(String servedUrl, String checksum, Instant now) {}

    public record PublishedPost(UUID postId) {}

    /**
     * Every way authoring refuses.
     *
     * <p>UPLOAD_UNUSABLE is ONE value covering four situations - the ticket never existed, belongs
     * to another owner, has expired, or has already been spent. An earlier version told the last
     * two apart, and the contract could not carry the difference: Problem.code is a closed enum
     * that nearly every response shares, so two new values measured 400 oasdiff errors (one per
     * code x operation x status). Collapsing them is not only the cheap answer - "that ticket is
     * not available to you" is true in all four, and the indistinguishability BA-070-T1 requires
     * between somebody else's id and one that never existed simply extends to the other two.
     *
     * <p>WHAT IS GIVEN UP, NAMED: a client that submits twice with DIFFERENT idempotency keys is
     * told the ticket is gone rather than that its post exists. An honest retry reuses its key and
     * the idempotency guard replays the original 201, so the case that remains is a client bug -
     * and the cost of that bug is a duplicate post rather than a lost one.
     */
    public enum AuthoringRejection {
        UPLOAD_UNUSABLE,
        UPLOAD_MISSING,
        IMAGE_REJECTED
    }

    /** Thrown for every refusal, never for a programming error. */
    public static final class AuthoringRejectedException extends RuntimeException {
        private final transient AuthoringRejection rejection;

        public AuthoringRejectedException(AuthoringRejection rejection) {
            super(rejection.name());
            this.rejection = rejection;
        }

        public AuthoringRejection rejection() {
            return rejection;
        }
    }
}
