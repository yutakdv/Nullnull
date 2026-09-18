package io.nullnull.optimization.application;

import io.nullnull.shared.cursor.SignedCursorCodec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Signing material for the optimization history cursor, alongside the trip and feed listings that
 * already have their own.
 *
 * <p>Per-listing rather than one shared component, which is the shape TripCursorProperties and
 * FeedCursorProperties established: the CONTEXT and SORT_VERSION below are what stop a cursor cut
 * for one listing from resuming another, and they are only meaningful if each listing owns them.
 * The secret is shared because it is one deployment secret; what is not shared is the claim set.
 */
@Component
public final class OptimizationCursorProperties {

    private static final String HMAC = "HmacSHA256";
    private static final String KEY_ID = "optimization-v1";
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    /** Bumped when the ordering changes, so a cursor cut by the old sort stops verifying. */
    public static final int SORT_VERSION = 1;

    /**
     * The listing a cursor belongs to. A history cursor replayed at another listing fails to decode.
     *
     * <p>The operationId, so the value a reader finds in the contract is the value in the claim.
     */
    public static final String CONTEXT = "listOptimizationHistory";

    private final byte[] cursorSecret;
    private final SignedCursorCodec cursorCodec;

    public OptimizationCursorProperties(@Value("${NULLNULL_CURSOR_SECRET:}") String configuredSecret,
            Environment environment) {
        String secret = Objects.requireNonNull(configuredSecret, "configuredSecret").trim();
        if (secret.isEmpty()) {
            if (!developmentProfile(environment)) {
                throw new IllegalArgumentException(
                        "NULLNULL_CURSOR_SECRET is required to sign optimization history cursors");
            }
            secret = ephemeralSecret();
        }
        this.cursorSecret = secret.getBytes(StandardCharsets.UTF_8);
        if (cursorSecret.length < 32) {
            throw new IllegalArgumentException("NULLNULL_CURSOR_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.cursorCodec = new SignedCursorCodec(cursorSecret, KEY_ID);
    }

    public SignedCursorCodec cursorCodec() {
        return cursorCodec;
    }

    public String keyId() {
        return KEY_ID;
    }

    public Duration cursorTtl() {
        return CURSOR_TTL;
    }

    /** HMAC so the opaque cursor never carries the owner's raw identifier. */
    public String ownerBinding(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(cursorSecret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(ownerId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    private static boolean developmentProfile(Environment environment) {
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        return profiles.length == 1 && Arrays.asList("local", "test", "integration").contains(profiles[0]);
    }

    private static String ephemeralSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
