package io.nullnull.catalog.application;

import io.nullnull.shared.cursor.SignedCursorCodec;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
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
 * Keeps the locally implemented catalog projection fail-closed until the release operator records
 * the required staging KTO provenance and deliberately enables it. A production process may run
 * with the projection disabled and no cursor secret; enabling it without a separate runtime secret
 * fails startup instead of creating forgeable cursors.
 */
@Component
public final class CatalogPublicationProperties {

    private static final String HMAC = "HmacSHA256";
    private static final String KEY_ID = "catalog-v1";
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    private final boolean publicEnabled;
    private final byte[] cursorSecret;
    private final SignedCursorCodec cursorCodec;

    public CatalogPublicationProperties(@Value("${nullnull.catalog.public-enabled:false}") boolean publicEnabled,
            @Value("${NULLNULL_CURSOR_SECRET:}") String configuredSecret, Environment environment) {
        this.publicEnabled = publicEnabled;
        String secret = Objects.requireNonNull(configuredSecret, "configuredSecret").trim();
        if (secret.isEmpty()) {
            if (publicEnabled && !developmentProfile(environment)) {
                throw new IllegalArgumentException("NULLNULL_CURSOR_SECRET is required when catalog publication is enabled");
            }
            secret = ephemeralSecret();
        }
        this.cursorSecret = secret.getBytes(StandardCharsets.UTF_8);
        if (cursorSecret.length < 32) {
            throw new IllegalArgumentException("NULLNULL_CURSOR_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.cursorCodec = new SignedCursorCodec(cursorSecret, KEY_ID);
    }

    public void requirePublicProjection() {
        if (!publicEnabled) {
            throw new ApiException(ProblemCode.ROUTE_UNAVAILABLE,
                    "Canonical place projection is not available yet.");
        }
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

    /** HMAC keeps the raw owner UUID out of the opaque cursor payload. */
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
