package io.nullnull.shared.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REC-FEED-04 cursor tamper/owner/expiry. The key here is a literal test value, never a real secret. */
@DisplayName("REC-FEED-04 cursor tamper/owner/expiry")
class SignedCursorCodecTest {

    static final byte[] KEY = "test-only-cursor-secret-32-bytes!".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    final SignedCursorCodec codec = new SignedCursorCodec(KEY, "k1");
    final CursorClaims claims = new CursorClaims("snap-1", 40, "owner-binding-a", "feed:trip-1", 1,
            NOW.plusSeconds(900), "k1");

    @Test
    void roundTripIsOpaqueAndStable() {
        String cursor = codec.encode(claims);
        assertThat(cursor).doesNotContain("owner-binding-a").matches("^[A-Za-z0-9_-]+$");
        assertThat(codec.encode(claims)).isEqualTo(cursor);
        assertThat(codec.decode(cursor, NOW, "owner-binding-a", "feed:trip-1")).isEqualTo(claims);
    }

    @Test
    void tamperedPayloadOrSignatureIsInvalid() {
        String cursor = codec.encode(claims);
        String flipped = cursor.substring(0, 10) + (cursor.charAt(10) == 'A' ? 'B' : 'A') + cursor.substring(11);
        assertThatThrownBy(() -> codec.decode(flipped, NOW, "owner-binding-a", "feed:trip-1"))
                .isInstanceOf(CursorException.class)
                .extracting(e -> ((CursorException) e).problem()).isEqualTo(ProblemCode.CURSOR_INVALID);
        assertThatThrownBy(() -> codec.decode("not-a-cursor", NOW, "owner-binding-a", "feed:trip-1"))
                .isInstanceOf(CursorException.class);
    }

    @Test
    void otherOwnerOrOtherContextIsInvalid() {
        String cursor = codec.encode(claims);
        assertThatThrownBy(() -> codec.decode(cursor, NOW, "owner-binding-b", "feed:trip-1"))
                .isInstanceOf(CursorException.class)
                .extracting(e -> ((CursorException) e).problem()).isEqualTo(ProblemCode.CURSOR_INVALID);
        assertThatThrownBy(() -> codec.decode(cursor, NOW, "owner-binding-a", "feed:trip-2"))
                .isInstanceOf(CursorException.class)
                .extracting(e -> ((CursorException) e).problem()).isEqualTo(ProblemCode.CURSOR_INVALID);
    }

    @Test
    void expiryIsDistinctFromInvalidAndKeyIdMustMatch() {
        String cursor = codec.encode(claims);
        assertThatThrownBy(() -> codec.decode(cursor, NOW.plusSeconds(901), "owner-binding-a", "feed:trip-1"))
                .isInstanceOf(CursorException.class)
                .extracting(e -> ((CursorException) e).problem()).isEqualTo(ProblemCode.CURSOR_EXPIRED);
        SignedCursorCodec rotated = new SignedCursorCodec(KEY, "k2");
        assertThatThrownBy(() -> rotated.decode(cursor, NOW, "owner-binding-a", "feed:trip-1"))
                .isInstanceOf(CursorException.class)
                .extracting(e -> ((CursorException) e).problem()).isEqualTo(ProblemCode.CURSOR_INVALID);
    }

    @Test
    void aShortSecretIsRefusedAndCursorProblemsAreTheTwoPublishedCodes() {
        assertThatThrownBy(() -> new SignedCursorCodec("too-short".getBytes(StandardCharsets.UTF_8), "k1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorException(ProblemCode.NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClaimNeverCarriesTheFieldSeparator() {
        assertThatThrownBy(() -> new CursorClaims("snap|1", 40, "owner-binding-a", "feed:trip-1", 1,
                NOW.plusSeconds(900), "k1")).isInstanceOf(IllegalArgumentException.class);
    }
}
