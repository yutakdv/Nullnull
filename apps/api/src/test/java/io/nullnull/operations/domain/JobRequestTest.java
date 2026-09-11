package io.nullnull.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BA-005: the column bounds of {@code background_jobs} are enforced before the insert, not by it. */
@DisplayName("BA-005 job request bounds")
class JobRequestTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    @Test
    void aReadyJobIsAvailableImmediately() {
        JobRequest request = ready("deletion", "deletion:owner:" + UUID.randomUUID(), 5);
        assertThat(request.availableAt()).isEqualTo(NOW);
        assertThat(request.createdAt()).isEqualTo(NOW);
    }

    @Test
    void theTypeMustBeALowerCaseIdentifier() {
        assertThatThrownBy(() -> ready("Deletion", "k", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ready("", "k", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ready("a".repeat(JobRequest.TYPE_MAX_LENGTH + 1), "k", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> ready("optimization-item", "k", 1)).doesNotThrowAnyException();
    }

    @Test
    void theDeduplicationKeyExcludesFreeTextAndOverLongValues() {
        assertThatThrownBy(() -> ready("deletion", "owner 1", 1))
                .isInstanceOf(IllegalArgumentException.class)
                // The key can name a resource, so the rejected value never reaches the message.
                .hasMessageNotContaining("owner 1");
        assertThatThrownBy(() -> ready("deletion", "k".repeat(JobRequest.DEDUPLICATION_KEY_MAX_LENGTH + 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> ready("deletion", "k".repeat(JobRequest.DEDUPLICATION_KEY_MAX_LENGTH), 1))
                .doesNotThrowAnyException();
    }

    @Test
    void attemptsAreBoundedOnBothSides() {
        assertThatThrownBy(() -> ready("deletion", "k", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ready("deletion", "k", JobRequest.MAX_ATTEMPTS_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(JobRequest.MAX_ATTEMPTS_LIMIT));
    }

    @Test
    void aJobCannotBecomeAvailableBeforeItExists() {
        assertThatThrownBy(() -> new JobRequest(UUID.randomUUID(), "deletion", "k", JobPayload.empty(),
                1, NOW.minusSeconds(1), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JobRequest ready(String type, String key, int maxAttempts) {
        return JobRequest.ready(UUID.randomUUID(), type, key, JobPayload.empty(), maxAttempts, NOW);
    }
}
