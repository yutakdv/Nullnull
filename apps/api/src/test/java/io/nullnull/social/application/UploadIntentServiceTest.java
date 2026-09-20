package io.nullnull.social.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.social.application.UploadIntentService.UploadRejectedException;
import io.nullnull.social.application.UploadIntentService.UploadRejection;
import io.nullnull.social.domain.UploadIntent;
import io.nullnull.social.domain.UploadIntentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-082: what the service refuses BEFORE it signs anything.
 *
 * <p>The byte ceiling was the clause with no test. ImageSanitiser proves the DIMENSION ceiling, and
 * the two are easy to mistake for one: a caller can declare four kilobytes and upload four
 * megabytes of 8x8 pixels, and it is the declared length - bound into the signature - that the
 * object store enforces. A ceiling nothing asserts is a ceiling that can be raised by accident.
 *
 * <p>Every case here also asserts that NOTHING was signed and NOTHING was stored. A refusal that
 * still handed back a URL would be a signed write into this owner's prefix for an upload we said
 * no to.
 */
@DisplayName("BA-082 upload ticket issuing")
class UploadIntentServiceTest {

    private static final String CHECKSUM = "a".repeat(64);
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final RecordingStore store = new RecordingStore();
    private final RecordingStorage storage = new RecordingStorage();
    private final UploadIntentService service = new UploadIntentService(store, storage,
            new UploadProperties(MAX_BYTES, 2048, java.util.Set.of("image/jpeg", "image/png"),
                    Duration.ofMinutes(15)),
            Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("BA-082-T10 an upload declaring more than the ceiling is refused before anything is signed")
    void anOversizeDeclarationIsRefused() {
        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "image/jpeg", MAX_BYTES + 1, CHECKSUM))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).rejection())
                .isEqualTo(UploadRejection.TOO_LARGE);
        assertThat(storage.signed).isEmpty();
        assertThat(store.inserted).isEmpty();
        // The ceiling itself is allowed, or "refuses everything" would pass the assertion above.
        assertThatCode(() -> service.issue(UUID.randomUUID(), "image/jpeg", MAX_BYTES, CHECKSUM))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-082-T11 a zero or negative declared length is refused")
    void anEmptyDeclarationIsRefused() {
        for (long declared : new long[] {0L, -1L, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "image/jpeg", declared, CHECKSUM))
                    .as("declared length %d", declared)
                    .isInstanceOf(UploadRejectedException.class)
                    .extracting(e -> ((UploadRejectedException) e).rejection())
                    .isEqualTo(UploadRejection.TOO_LARGE);
        }
        assertThat(storage.signed).isEmpty();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    @DisplayName("BA-082-T12 a format outside the offered vocabulary is refused before anything is signed")
    void anUnofferedFormatIsRefused() {
        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "image/webp", 1024, CHECKSUM))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).rejection())
                .isEqualTo(UploadRejection.UNSUPPORTED_CONTENT_TYPE);
        assertThat(storage.signed).isEmpty();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    @DisplayName("BA-082-T13 a checksum that is not 64 lowercase hex characters is refused")
    void aMalformedChecksumIsRefused() {
        for (String bad : List.of("", "A".repeat(64), "a".repeat(63), "a".repeat(65), "zz" + "a".repeat(62))) {
            assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "image/jpeg", 1024, bad))
                    .as("checksum %s", bad)
                    .isInstanceOf(UploadRejectedException.class)
                    .extracting(e -> ((UploadRejectedException) e).rejection())
                    .isEqualTo(UploadRejection.MALFORMED_CHECKSUM);
        }
        assertThat(storage.signed).isEmpty();
        assertThat(store.inserted).isEmpty();
    }

    @Test
    @DisplayName("BA-082-T14 the signed key carries the owner and an id the caller never chose")
    void theKeyIsOursNotTheCallers() {
        UUID ownerId = UUID.randomUUID();
        var issued = service.issue(ownerId, "image/png", 2048, CHECKSUM);

        assertThat(storage.signed).hasSize(1);
        String key = storage.signed.getFirst();
        assertThat(key).startsWith("quarantine/" + ownerId + "/").endsWith(issued.uploadId().toString());
        // Nothing the request carried appears in the key: the owner came from the session and the
        // id was generated here, which is why there is no path to manipulate.
        assertThat(store.inserted).hasSize(1);
        assertThat(store.inserted.getFirst().quarantineKey()).isEqualTo(key);
    }

    private static final class RecordingStore implements UploadIntentStore {
        private final List<UploadIntent> inserted = new ArrayList<>();

        @Override
        public void insert(UploadIntent intent) {
            inserted.add(intent);
        }

        @Override
        public Optional<UploadIntent> find(UUID id) {
            return inserted.stream().filter(i -> i.id().equals(id)).findFirst();
        }

        @Override
        public boolean claim(UUID id, Instant at) {
            return true;
        }

        @Override
        public boolean reject(UUID id, Instant at) {
            return true;
        }
    }

    private static final class RecordingStorage implements ObjectStorage {
        private final List<String> signed = new ArrayList<>();

        @Override
        public PresignedUpload presignQuarantinePut(String key, String contentType,
                long contentLength, Duration ttl) {
            signed.add(key);
            return new PresignedUpload("https://storage.test/" + key, "PUT",
                    java.util.Map.of("Content-Type", contentType), Instant.now().plus(ttl));
        }

        @Override
        public byte[] readQuarantined(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String publish(String key, byte[] bytes, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteQuarantined(String key) {
            throw new UnsupportedOperationException();
        }
    }
}
