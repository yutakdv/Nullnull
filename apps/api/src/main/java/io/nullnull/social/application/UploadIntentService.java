package io.nullnull.social.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.social.application.ObjectStorage.PresignedUpload;
import io.nullnull.social.domain.UploadIntent;
import io.nullnull.social.domain.UploadIntentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Signs one upload at a time and remembers that it did.
 *
 * <p>The caller never names a storage path. It asks for permission to upload something of a stated
 * type and length, and gets back a URL it cannot have chosen - which is what makes path
 * manipulation (BA-082-T14) a question with no surface: there is nothing in the request that a
 * path is built from except the owner we derived ourselves and an id we generated.
 */
@Service
public class UploadIntentService {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final String CREATE_ROUTE = "POST /posts/images/uploads";
    private static final Duration PRESIGN_BOUND = Duration.ofSeconds(30);

    private final UploadIntentStore store;
    private final ObjectStorage storage;
    private final UploadProperties properties;
    private final Clock clock;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;

    public UploadIntentService(UploadIntentStore store, ObjectStorage storage,
            UploadProperties properties, Clock clock, IdempotencyGuard idempotency,
            ObjectMapper json) {
        this.store = store;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.idempotency = idempotency;
        this.json = json;
    }

    /**
     * @throws UploadRejectedException for every refusal, with the reason the API layer maps
     */
    public IssuedUpload issue(UUID ownerId, String idempotencyKey, String contentType,
            long contentLength,
            String checksumSha256) {
        if (!properties.accepts(contentType)) {
            throw new UploadRejectedException(UploadRejection.UNSUPPORTED_CONTENT_TYPE);
        }
        // This looks redundant with the same bound in the UploadIntent constructor, and it is not.
        // This one decides the contract-level rejection (TOO_LARGE); the domain guard throws
        // IllegalArgumentException, so deleting this one sends FE a 500 from that exception instead
        // of the refusal the contract declares. BA-082-T11 measures exactly that difference - it
        // asserts the rejection KIND, so removing this line turns it red. Measured: deleting these
        // three lines reddens BA-082-T11 and BA-082-T10, and nothing else. The upper bound has no
        // sibling at all - the domain guard only rejects lengths below one.
        if (contentLength < 1 || contentLength > properties.maxBytes()) {
            throw new UploadRejectedException(UploadRejection.TOO_LARGE);
        }
        if (checksumSha256 == null || !SHA256_HEX.matcher(checksumSha256).matches()) {
            throw new UploadRejectedException(UploadRejection.MALFORMED_CHECKSUM);
        }

        String fingerprint = RequestFingerprint.of("createPostImageUpload", Map.of(),
                json.writeValueAsString(new UploadPayload(contentType, contentLength, checksumSha256)))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(ownerId, CREATE_ROUTE,
                idempotencyKey, fingerprint,
                new IdempotencyGuard.Prelude<>(PRESIGN_BOUND,
                        () -> prepare(ownerId, contentType, contentLength, checksumSha256)),
                prepared -> {
                    store.insert(prepared.intent());
                    return new IdempotencyGuard.CommandOutcome<>(201,
                            new IssuedUpload(prepared.intent().id(), prepared.presigned()));
                },
                value -> value);
        return json.readValue(guarded.body(), IssuedUpload.class);
    }

    private PreparedUpload prepare(UUID ownerId, String contentType, long contentLength,
            String checksumSha256) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        // The owner is in the key so that an object can be attributed without reading the database,
        // and the id makes it unguessable. Neither component comes from the request: the owner is
        // derived from the session (invariant 11) and the id is ours.
        String key = "quarantine/" + ownerId + "/" + id;
        Instant expiresAt = now.plus(properties.presignTtl());

        // Signed BEFORE the row is written, so a signing failure leaves no intent claiming an
        // upload that was never possible. The opposite order fails the other way round and the
        // other way round is the one that lies to the caller.
        PresignedUpload presigned =
                storage.presignQuarantinePut(key, contentType, contentLength, properties.presignTtl());

        UploadIntent intent = new UploadIntent(id, ownerId, UploadIntentStatus.PENDING, contentType,
                contentLength, checksumSha256, key, now, expiresAt, null);
        return new PreparedUpload(intent, presigned);
    }

    private record UploadPayload(String contentType, long contentLength, String checksumSha256) {}

    private record PreparedUpload(UploadIntent intent, PresignedUpload presigned) {}

    /** What the caller needs to perform the upload, and the id it hands back afterwards. */
    public record IssuedUpload(UUID uploadId, PresignedUpload upload) {}

    /** Every way issuing refuses. */
    public enum UploadRejection {
        UNSUPPORTED_CONTENT_TYPE,
        TOO_LARGE,
        MALFORMED_CHECKSUM
    }

    /** Thrown for every refusal, never for a programming error. */
    public static final class UploadRejectedException extends RuntimeException {
        private final transient UploadRejection rejection;

        public UploadRejectedException(UploadRejection rejection) {
            super(rejection.name());
            this.rejection = rejection;
        }

        public UploadRejection rejection() {
            return rejection;
        }
    }
}
