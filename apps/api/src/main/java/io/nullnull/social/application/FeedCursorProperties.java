package io.nullnull.social.application;

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
 * The signing material for listFeed cursors.
 *
 * <p>Its own key id, not the catalog's: the two listings page over different data with different
 * sort rules, and a cursor minted for one must not verify against the other. Sharing the configured
 * secret is fine because the key id travels inside the claims and is checked on decode.
 *
 * <p>Trips are always owner-scoped, so unlike the catalog there is no publication switch. A process
 * without a configured secret still starts outside production - a development run that mints
 * ephemeral cursors is preferable to one that cannot list trips at all - but the secret is required
 * in every other profile, because an ephemeral one silently invalidates every cursor on restart.
 */
@Component
public final class FeedCursorProperties {

    private static final String HMAC = "HmacSHA256";
    private static final String KEY_ID = "feed-v1";
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);
    /** Bumped when the ordering changes, so a cursor cut by the old sort stops verifying. */
    public static final int SORT_VERSION = io.nullnull.social.domain.FeedOrdering.SORT_VERSION;
    /** The listing a cursor belongs to. A trips cursor replayed at another listing fails to decode. */
    public static final String CONTEXT = "listFeed";

    private final byte[] cursorSecret;
    private final SignedCursorCodec cursorCodec;

    public FeedCursorProperties(@Value("${NULLNULL_CURSOR_SECRET:}") String configuredSecret,
            Environment environment) {
        String secret = Objects.requireNonNull(configuredSecret, "configuredSecret").trim();
        if (secret.isEmpty()) {
            if (!developmentProfile(environment)) {
                throw new IllegalArgumentException("NULLNULL_CURSOR_SECRET is required to sign feed cursors");
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
