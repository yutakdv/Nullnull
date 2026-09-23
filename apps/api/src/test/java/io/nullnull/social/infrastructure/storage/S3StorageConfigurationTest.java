package io.nullnull.social.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class S3StorageConfigurationTest {

    @Test
    void s3CallsFinishBeforeThePostPublishReservationCanExpire() {
        var properties = new S3StorageProperties("test-bucket", "ap-northeast-2",
                "https://example.test");
        try (var client = new S3StorageConfiguration().s3Client(properties)) {
            var config = client.serviceClientConfiguration().overrideConfiguration();
            assertThat(config.apiCallTimeout()).contains(S3StorageConfiguration.S3_CALL_TIMEOUT);
            assertThat(config.apiCallAttemptTimeout())
                    .contains(S3StorageConfiguration.S3_ATTEMPT_TIMEOUT);
            assertThat(S3StorageConfiguration.S3_CALL_TIMEOUT.multipliedBy(3))
                    .isLessThan(java.time.Duration.ofMinutes(2));
        }
    }
}
