package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-002: which projections PostgreSQL {@code jsonb} really cannot store.
 *
 * <p>Only a real U+0000 code point is unstorable. A response whose text happens to contain the six
 * characters {@code \}{@code u0000} is ordinary text that PostgreSQL stores without complaint, so
 * refusing it would turn a legal response into an unretryable INTERNAL_ERROR.
 *
 * <p>Every case is checked twice: once on the exact string Jackson produces, so the assumption about
 * Jackson's escaping is verified rather than assumed, and once on the pure function directly.
 */
@DisplayName("BA-002 unstorable projection detection")
class IdempotencyGuardNulDetectionTest {

    /** The six characters, split so this source file is not itself a unicode escape (JLS 3.3). */
    private static final String SIX_CHARACTER_TEXT = "\\" + "u0000";

    /** One real U+0000, built without a source escape. */
    private static final String REAL_NUL = String.valueOf((char) 0);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a real U+0000 in a string field is unstorable")
    void aRealNulIsDetected() {
        String serialised = json.writeValueAsString(Map.of("a", "before" + REAL_NUL + "after"));
        assertThat(serialised).isEqualTo("{\"a\":\"before" + SIX_CHARACTER_TEXT + "after\"}");
        assertThat(IdempotencyGuard.containsRealNul(serialised)).isTrue();
    }

    @Test
    @DisplayName("the same six characters as literal text are storable")
    void theSixCharacterTextIsNotARealNul() {
        String serialised = json.writeValueAsString(Map.of("a", SIX_CHARACTER_TEXT));
        // Jackson doubles the literal backslash, which is what tells the two cases apart.
        assertThat(serialised).isEqualTo("{\"a\":\"\\" + SIX_CHARACTER_TEXT + "\"}");
        assertThat(IdempotencyGuard.containsRealNul(serialised)).isFalse();
    }

    @Test
    @DisplayName("a literal backslash in front of a real U+0000 does not hide it")
    void anOddRunBeforeARealNulIsStillARealNul() {
        String serialised = json.writeValueAsString(Map.of("a", "\\" + REAL_NUL));
        assertThat(serialised).isEqualTo("{\"a\":\"\\\\" + SIX_CHARACTER_TEXT + "\"}");
        assertThat(IdempotencyGuard.containsRealNul(serialised)).isTrue();
    }

    @Test
    @DisplayName("two literal backslashes in front of the six characters are still text")
    void anEvenRunBeforeTheTextIsStillText() {
        String serialised = json.writeValueAsString(Map.of("a", "\\\\" + SIX_CHARACTER_TEXT));
        assertThat(IdempotencyGuard.containsRealNul(serialised)).isFalse();
    }

    @Test
    @DisplayName("a real U+0000 after the literal text is still found")
    void aLaterRealNulIsNotMaskedByAnEarlierLiteralOne() {
        String serialised = json.writeValueAsString(Map.of("a", SIX_CHARACTER_TEXT + REAL_NUL));
        assertThat(IdempotencyGuard.containsRealNul(serialised)).isTrue();
    }

    @Test
    @DisplayName("an ordinary projection carries no U+0000")
    void anOrdinaryProjectionIsStorable() {
        assertThat(IdempotencyGuard.containsRealNul(
                json.writeValueAsString(Map.of("requestId", "r-1")))).isFalse();
        assertThat(IdempotencyGuard.containsRealNul("")).isFalse();
        // A truncated escape is neither: it is not the six characters at all.
        assertThat(IdempotencyGuard.containsRealNul("\\" + "u000")).isFalse();
    }
}
