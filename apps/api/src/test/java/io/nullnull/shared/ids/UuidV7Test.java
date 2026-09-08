package io.nullnull.shared.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void encodesVersionVariantAndTimestamp() {
        long millis = 1_788_000_000_000L;
        UUID id = UuidV7.create(millis, new Random(42));
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(UuidV7.timestampMillis(id)).isEqualTo(millis);
    }

    @Test
    void laterMillisecondsSortAfterEarlierOnes() {
        UUID earlier = UuidV7.create(1_000L, new Random(1));
        UUID later = UuidV7.create(2_000L, new Random(1));
        assertThat(later.compareTo(earlier)).isPositive();
        assertThat(later.toString().compareTo(earlier.toString())).isPositive();
    }

    @Test
    void rejectsTimestampsOutsideFortyEightBits() {
        assertThatThrownBy(() -> UuidV7.create(-1L, new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UuidV7.create(1L << 48, new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timestampReadRejectsOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.fromString("018f3f8e-9b67-4a21-8d31-31d315b93901")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
