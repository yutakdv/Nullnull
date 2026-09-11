package io.nullnull.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Receipt-scoped bearer regeneration: no token needs to be persisted for replay. */
public final class DeletionTokens {
    private final SecretKeySpec key;

    public DeletionTokens(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Deletion token secret must contain at least 32 bytes.");
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String issue(UUID requestId, Instant expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] message = (requestId + "|" + expiresAt.getEpochSecond())
                    .getBytes(StandardCharsets.US_ASCII);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Deletion token signing unavailable.");
        }
    }

    public byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Deletion token hashing unavailable.");
        }
    }

    public boolean matches(String supplied, UUID requestId, Instant expiresAt) {
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(supplied), hash(issue(requestId, expiresAt)));
    }
}
