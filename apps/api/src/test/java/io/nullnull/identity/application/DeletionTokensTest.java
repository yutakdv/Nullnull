package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeletionTokensTest {
    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test void repeatableAndBoundToReceiptExpiryAndSecret() {
        var tokens = new DeletionTokens(secret());
        UUID receipt = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-09-16T00:00:00Z");
        String bearer = tokens.issue(receipt, expiry);
        assertThat(tokens.issue(receipt, expiry)).isEqualTo(bearer);
        assertThat(tokens.hash(bearer)).hasSize(32);
        assertThat(tokens.matches(bearer, receipt, expiry)).isTrue();
        assertThat(tokens.matches(bearer, UUID.randomUUID(), expiry)).isFalse();
        assertThat(tokens.matches(bearer, receipt, expiry.plusSeconds(1))).isFalse();
        assertThat(new DeletionTokens(secret()).matches(bearer, receipt, expiry)).isFalse();
        assertThat(tokens.matches(null, receipt, expiry)).isFalse();
        assertThat(tokens.matches(bearer + "=", receipt, expiry)).isFalse();
    }

    @Test void rejectsMissingAndShortSecretsWithoutEchoingThem() {
        assertThatThrownBy(() -> new DeletionTokens(null)).isInstanceOf(IllegalArgumentException.class);
        String tooShort = secret().substring(0, 31);
        assertThatThrownBy(() -> new DeletionTokens(tooShort))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(tooShort);
    }
}
