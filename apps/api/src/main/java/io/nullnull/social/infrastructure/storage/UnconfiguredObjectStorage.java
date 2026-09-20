package io.nullnull.social.infrastructure.storage;

import io.nullnull.social.application.ObjectStorage;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The port when no object store is configured.
 *
 * <p>This is NOT a fallback that carries on. Every method throws, naming the three properties that
 * are missing, so a deployment that forgot them finds out on the first upload with a message that
 * says what to set - rather than accepting a post and losing the image, which is what a no-op
 * implementation would do.
 *
 * <p>It exists so that the hundreds of suites which have nothing to do with uploads still start.
 * The alternative - requiring the S3 properties everywhere - would put a bucket name into every
 * test context that never touches one.
 *
 * <p>THE CONDITION IS THE MIRROR OF {@link S3ObjectStorage}'s, not {@code @ConditionalOnMissingBean}.
 * Outside auto-configuration, "missing bean" is answered in whatever order configuration classes
 * happen to be read, and the first attempt at this class failed exactly there. Two conditions that
 * are each other's negation over one property cannot both hold and cannot both fail, whatever the
 * order. A suite that wants its own storage stands a {@code @Primary} bean in front.
 */
@Component
@ConditionalOnProperty(name = "nullnull.upload.s3.bucket", matchIfMissing = true,
        havingValue = "never-set-in-any-environment")
public class UnconfiguredObjectStorage implements ObjectStorage {

    private static final String MESSAGE = "uploads are not configured: set nullnull.upload.s3.bucket,"
            + " nullnull.upload.s3.region and nullnull.upload.s3.public-base-url";

    @Override
    public PresignedUpload presignQuarantinePut(String key, String contentType, long contentLength,
            Duration ttl) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public byte[] readQuarantined(String key) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public String publish(String key, byte[] bytes, String contentType) {
        throw new IllegalStateException(MESSAGE);
    }

    @Override
    public void deleteQuarantined(String key) {
        throw new IllegalStateException(MESSAGE);
    }
}
