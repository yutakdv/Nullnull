package io.nullnull.social.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UploadPropertiesTest {

    @Test
    void signedUrlMustExpireBeforeConfiguredIdempotencyRecord() {
        assertThatThrownBy(() -> new UploadProperties(4_194_304, 2048,
                Set.of("image/jpeg"), Duration.ofMinutes(15), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nullnull.upload.presign-ttl");
        assertThatThrownBy(() -> new UploadProperties(4_194_304, 2048,
                Set.of("image/jpeg"), Duration.ofMinutes(15), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new UploadProperties(4_194_304, 2048,
                Set.of("image/jpeg"), Duration.ofMinutes(15), Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }
}
