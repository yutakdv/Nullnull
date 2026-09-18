package io.nullnull.shared.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REC-FEED-04 cursor tamper/owner/expiry. The key here is a literal test value, never a real secret. */
@DisplayName("cursor tamper/owner/expiry (REC-FEED family, clause 04)")
class SignedCursorCodecTest {

    static final byte[] KEY = "test-only-cursor-secret-32-bytes!".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    static final UUID LAST_ROW = UUID.fromString("018f4b20-1a44-7e11-9c02-5d7e3f1a2b01");

    final SignedCursorCodec codec = new SignedCursorCodec(KEY, "k1");
    final CursorClaims claims = new CursorClaims("snap-1",
            CursorSortKey.of(Instant.parse("2026-09-05T11:00:00Z"), LAST_ROW).encode(),
            "owner-binding-a", "feed:trip-1", 1, NOW.plusSeconds(900), "k1");

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
        assertThatThrownBy(() -> new CursorClaims("snap|1", claims.sortKey(), "owner-binding-a",
                "feed:trip-1", 1, NOW.plusSeconds(900), "k1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * BA-027. An offset-era cursor carries an intact signature, the same claim count and the same
     * key id, so every check before this one passes it: the number where the sort key belongs is
     * the only thing left that can tell the two formats apart. Reading "40" as a place in the
     * listing would resume somewhere nobody asked for, which is worse than starting again.
     */
    @Test
    void anOffsetEraCursorIsRefusedRatherThanReadAsASortKey() {
        String payload = String.join("|", "snap-1", "40", "owner-binding-a", "feed:trip-1", "1",
                Long.toString(NOW.plusSeconds(900).getEpochSecond()), "k1");
        String signature = hmac(payload);
        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + signature).getBytes(StandardCharsets.UTF_8));

        CursorClaims decoded = codec.decode(legacy, NOW, "owner-binding-a", "feed:trip-1");
        assertThat(decoded.sortKey()).as("the signature and every claim check still pass").isEqualTo("40");
        assertThatThrownBy(() -> CursorSortKey.decode(decoded.sortKey()))
                .isInstanceOf(CursorException.class)
                .extracting(error -> ((CursorException) error).problem()).isEqualTo(ProblemCode.CURSOR_INVALID);
    }

    /**
     * A sort key survives a value carrying the payload separator, the key separator and a
     * non-ASCII name. The place-search key is a place name, so all three are reachable input.
     */
    @Test
    void aSortKeyValueSurvivesEverySeparatorItCouldContain() {
        CursorSortKey key = new CursorSortKey("a|b:c 경복궁", LAST_ROW);
        String cursor = codec.encode(new CursorClaims("snap-1", key.encode(), "owner-binding-a",
                "feed:trip-1", 1, NOW.plusSeconds(900), "k1"));
        assertThat(CursorSortKey.decode(
                codec.decode(cursor, NOW, "owner-binding-a", "feed:trip-1").sortKey())).isEqualTo(key);
    }

    private static String hmac(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(KEY, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
