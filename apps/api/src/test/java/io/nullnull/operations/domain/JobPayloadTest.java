package io.nullnull.operations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-005: "payload에는 domain ID만" (docs/architecture/ERD.md §4) is a check, not a convention.
 *
 * <p>The cases below are the ones the rule exists for: pasted itinerary text, a coordinate pair and a
 * provider secret. Each of them fails the identifier shape, so a payload carrying one cannot be
 * constructed, let alone stored.
 */
@DisplayName("BA-005 job payload carries domain identifiers only")
class JobPayloadTest {

    @Test
    void identifiersAreAccepted() {
        assertThatCode(() -> JobPayload.of(Map.of(
                "ownerId", UUID.randomUUID().toString(),
                "tripId", "01890c4f-0000-7000-8000-000000000001",
                "sourceCode", "KTO_AREA_BASED",
                "runId", "run:42")))
                .doesNotThrowAnyException();
    }

    @Test
    void freeTextIsRejected() {
        assertThatThrownBy(() -> JobPayload.of(Map.of("note", "경복궁 09:00 도착 후 광화문")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note")
                // The rejected value must not be echoed: it is exactly the user text being kept out.
                .hasMessageNotContaining("경복궁");
    }

    @Test
    void aCoordinatePairIsRejected() {
        assertThatThrownBy(() -> JobPayload.of(Map.of("location", "37.579617,126.977041")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSecretLikeValueIsRejectedByLength() {
        assertThatThrownBy(() -> JobPayload.of(Map.of("key", "s".repeat(65))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyThatIsNotAFieldNameIsRejected() {
        assertThatThrownBy(() -> JobPayload.of(Map.of("owner id", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobPayload.of(Map.of("Owner", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyEntriesAreRejected() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index <= JobPayload.MAX_ENTRIES; index++) {
            values.put("id" + index, "v" + index);
        }
        assertThatThrownBy(() -> JobPayload.of(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(JobPayload.MAX_ENTRIES));
    }

    @Test
    void theStoredMapIsSortedAndUnmodifiable() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tripId", "t1");
        values.put("ownerId", "o1");
        JobPayload payload = JobPayload.of(values);
        assertThat(payload.values().keySet()).containsExactly("ownerId", "tripId");
        assertThatThrownBy(() -> payload.values().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(payload.get("tripId")).isEqualTo("t1");
        assertThat(payload.get("absent")).isNull();
    }

    @Test
    void anEmptyPayloadIsValid() {
        assertThat(JobPayload.empty().values()).isEmpty();
    }
}
