package io.nullnull.shared.cursor;

import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code base64url( payload "|" base64url(HMAC-SHA256(payload)) )}. The payload is a fixed-order
 * {@code '|'} join of the claims. The signature is verified before any claim is parsed or trusted;
 * owner, context and key-id mismatches are {@code CURSOR_INVALID}, and an intact but expired cursor is
 * {@code CURSOR_EXPIRED} so the FE can tell "not yours" from "start again".
 *
 * <p>Pure codec: {@code now} is passed in, no clock is read here, and the secret is injected by the
 * caller (BA-032 reads {@code NULLNULL_CURSOR_SECRET}); TTL and {@code sortVersion} enforcement belong
 * to that endpoint. The same key with another {@code keyId} is refused, so a rotation runs two codecs.
 */
public final class SignedCursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int CLAIM_COUNT = 7;

    private final SecretKeySpec key;
    private final String keyId;

    public SignedCursorCodec(byte[] secret, String keyId) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("cursor secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.keyId = java.util.Objects.requireNonNull(keyId, "keyId");
    }

    public String encode(CursorClaims claims) {
        String payload = String.join("|", claims.snapshotId(), Long.toString(claims.nextOrdinal()),
                claims.ownerBinding(), claims.context(), Integer.toString(claims.sortVersion()),
                Long.toString(claims.expiresAt().getEpochSecond()), claims.keyId());
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    public CursorClaims decode(String cursor, Instant now, String expectedOwnerBinding, String expectedContext) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        int split = decoded.lastIndexOf('|');
        if (split <= 0) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        String payload = decoded.substring(0, split);
        byte[] expectedSignature = sign(payload);
        byte[] actualSignature;
        try {
            actualSignature = Base64.getUrlDecoder().decode(decoded.substring(split + 1));
        } catch (IllegalArgumentException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != CLAIM_COUNT) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        CursorClaims claims;
        try {
            claims = new CursorClaims(parts[0], Long.parseLong(parts[1]), parts[2], parts[3],
                    Integer.parseInt(parts[4]), Instant.ofEpochSecond(Long.parseLong(parts[5])), parts[6]);
        } catch (IllegalArgumentException exception) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        if (!claims.keyId().equals(keyId) || !claims.ownerBinding().equals(expectedOwnerBinding)
                || !claims.context().equals(expectedContext)) {
            throw new CursorException(ProblemCode.CURSOR_INVALID);
        }
        if (!claims.expiresAt().isAfter(now)) {
            throw new CursorException(ProblemCode.CURSOR_EXPIRED);
        }
        return claims;
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }
}
