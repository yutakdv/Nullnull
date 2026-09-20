package io.nullnull.social.infrastructure.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Where uploaded covers live, as the deployment sets it.
 *
 * <p>A-058 with the owner's (A) choice (2026-09-20): the bucket the web edge already serves is
 * reused under its own prefixes rather than a new bucket and distribution being created. The API
 * task is granted {@code s3:PutObject} on the published prefix ONLY - it must not be able to
 * overwrite the application bundle the same bucket holds.
 *
 * <p>Every value fails the context when missing. A service that starts with no bucket would accept
 * an upload request and discover at signing time that it has nowhere to put it.
 */
@Component
@ConditionalOnProperty("nullnull.upload.s3.bucket")
public final class S3StorageProperties {

    private final String bucket;
    private final String region;
    private final String publicBaseUrl;

    public S3StorageProperties(
            @Value("${nullnull.upload.s3.bucket}") String bucket,
            @Value("${nullnull.upload.s3.region}") String region,
            @Value("${nullnull.upload.s3.public-base-url}") String publicBaseUrl) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("nullnull.upload.s3.bucket must be set");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("nullnull.upload.s3.region must be set");
        }
        // https only, and no trailing slash: the served URL is built by concatenation, and a base
        // that ends in one would produce a double slash that the CDN treats as a different key.
        if (publicBaseUrl == null || !publicBaseUrl.startsWith("https://")
                || publicBaseUrl.endsWith("/")) {
            throw new IllegalStateException(
                    "nullnull.upload.s3.public-base-url must be an https URL with no trailing slash");
        }
        this.bucket = bucket;
        this.region = region;
        this.publicBaseUrl = publicBaseUrl;
    }

    public String bucket() {
        return bucket;
    }

    public String region() {
        return region;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }
}
