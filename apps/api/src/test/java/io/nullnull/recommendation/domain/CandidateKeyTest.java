package io.nullnull.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CandidateKeyTest {

    private static final UUID PLACE = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93a01");

    @Test
    void placeOnlyKeyHasNoDateOrTime() {
        CandidateKey key = CandidateKey.ofPlace(PLACE);
        assertThat(key.hasDate()).isFalse();
        assertThat(key.hasTime()).isFalse();
    }

    @Test
    void timeWithoutDateIsRejected() {
        assertThatThrownBy(() -> new CandidateKey(PLACE, null, LocalTime.NOON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameInputsAreEqual() {
        LocalDate date = LocalDate.of(2026, 9, 12);
        assertThat(CandidateKey.ofDateTime(PLACE, date, LocalTime.of(10, 0)))
                .isEqualTo(new CandidateKey(PLACE, date, LocalTime.of(10, 0)));
    }
}
