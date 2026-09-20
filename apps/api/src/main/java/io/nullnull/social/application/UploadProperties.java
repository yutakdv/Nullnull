package io.nullnull.social.application;

import io.nullnull.social.domain.ImageFormat;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The ceilings an uploaded cover has to fit, and how long a signed URL lives.
 *
 * <p>These are config rather than constants in a migration for the reason V037 gives about its own
 * derivation: an applied migration cannot be edited, so a ceiling written into one could never be
 * raised. application.yaml carries the derivation; this class refuses a configuration that would
 * make the ceilings meaningless.
 *
 * <p>Every check here fails the context rather than correcting the value. A service that starts
 * with a silently repaired ceiling looks configured and is not.
 */
@Component
public final class UploadProperties {

    private final long maxBytes;
    private final int maxLongEdgePixels;
    private final Set<String> contentTypes;
    private final Duration presignTtl;

    public UploadProperties(
            @Value("${nullnull.upload.max-bytes}") long maxBytes,
            @Value("${nullnull.upload.max-long-edge-pixels}") int maxLongEdgePixels,
            @Value("${nullnull.upload.content-types}") Set<String> contentTypes,
            @Value("${nullnull.upload.presign-ttl}") Duration presignTtl) {
        if (maxBytes < 1) {
            throw new IllegalStateException("nullnull.upload.max-bytes must be positive");
        }
        if (maxLongEdgePixels < 1) {
            throw new IllegalStateException("nullnull.upload.max-long-edge-pixels must be positive");
        }
        if (contentTypes.isEmpty()) {
            throw new IllegalStateException("nullnull.upload.content-types must name at least one");
        }
        // A configured type with no decoder behind it would be advertised and then refused by the
        // sanitiser - the caller would be told the format is accepted and then told it is not.
        Set<String> unknown = new LinkedHashSet<>(contentTypes);
        unknown.removeIf(type -> ImageFormat.ofMediaType(type) != null);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "nullnull.upload.content-types names formats ImageFormat cannot handle: "
                            + unknown + "; adding one needs a codec, not a config change");
        }
        if (presignTtl.isNegative() || presignTtl.isZero()) {
            throw new IllegalStateException("nullnull.upload.presign-ttl must be positive");
        }
        // Bounded above by how long an idempotency record is kept (24h): an intent that outlived the
        // record making its consumption replay-safe would be a signed URL with nothing behind it.
        if (presignTtl.compareTo(Duration.ofHours(24)) >= 0) {
            throw new IllegalStateException(
                    "nullnull.upload.presign-ttl must stay under the 24h idempotency retention");
        }
        this.maxBytes = maxBytes;
        this.maxLongEdgePixels = maxLongEdgePixels;
        this.contentTypes = Set.copyOf(contentTypes);
        this.presignTtl = presignTtl;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int maxLongEdgePixels() {
        return maxLongEdgePixels;
    }

    public boolean accepts(String contentType) {
        return contentTypes.contains(contentType);
    }

    public Set<String> contentTypes() {
        return contentTypes;
    }

    public Duration presignTtl() {
        return presignTtl;
    }
}
