package io.nullnull.social.infrastructure.storage;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The two AWS clients {@link S3ObjectStorage} needs.
 *
 * <p>Credentials come from the default provider chain, which on ECS is the task role - so there is
 * no key in configuration, in an environment variable or in an image layer to leak.
 *
 * <p>THE WHOLE CLASS IS CONDITIONAL on the bucket being configured, and that is not an optional
 * feature flag - it is what keeps every unrelated suite bootable. The properties have no defaults
 * anywhere (a default bucket is a bucket somebody eventually writes to by accident), so without
 * them there is nothing to build a client from. When they are absent {@link UnconfiguredObjectStorage}
 * takes the port and refuses loudly at the point of use.
 *
 * <p>Both beans are also {@code @ConditionalOnMissingBean} so a suite can stand its own in front.
 * The gate runs with egress denied and must never construct a client that would try to reach AWS.
 */
@Configuration
@ConditionalOnProperty("nullnull.upload.s3.bucket")
public class S3StorageConfiguration {

    // PostAuthoringService has a two-minute prelude lease. Its three S3 operations must each
    // terminate within this bound so a same-key retry cannot take over while one is still running.
    static final Duration S3_CALL_TIMEOUT = Duration.ofSeconds(30);
    static final Duration S3_ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean
    public S3Client s3Client(S3StorageProperties properties) {
        return S3Client.builder().region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(S3_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(S3_ATTEMPT_TIMEOUT))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}
