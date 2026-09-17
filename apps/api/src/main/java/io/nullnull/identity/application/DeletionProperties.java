package io.nullnull.identity.application;

import io.nullnull.operations.domain.JobRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class DeletionProperties {
    private static final Logger log = LoggerFactory.getLogger(DeletionProperties.class);

    public final Duration statusTtl;
    public final Duration tombstoneRetention;
    public final int retryLimit;
    public final DeletionTokens tokens;

    public DeletionProperties(@Value("${nullnull.deletion.status-token-ttl}") String statusTtl,
            @Value("${nullnull.deletion.tombstone-retention}") String tombstoneRetention,
            @Value("${nullnull.deletion.retry-limit}") String retryLimit,
            @Value("${NULLNULL_DELETION_TOKEN_SECRET:}") String secret,
            Environment environment) {
        this.statusTtl = positive("APP_DELETION_STATUS_TOKEN_TTL", statusTtl);
        this.tombstoneRetention = positive("APP_DELETION_TOMBSTONE_RETENTION", tombstoneRetention);
        if (this.tombstoneRetention.compareTo(this.statusTtl) < 0) {
            throw new IllegalArgumentException("Deletion tombstone retention must cover status token TTL.");
        }
        this.retryLimit = count("APP_DELETION_RETRY_LIMIT", retryLimit, 1, JobRequest.MAX_ATTEMPTS_LIMIT);
        // An empty secret is a DEFINED value here, not a missing one: under a development profile it
        // means "mint a key for this process". That substitution used to happen without a word, and a
        // fallback with no trace is the one shape this repository forbids - a deletion status token
        // issued before a restart silently stops verifying after it, and nothing said why.
        // The NAME is printed and the value never is. A variable name is not a secret; this value is.
        boolean ephemeral = secret.isEmpty() && developmentProfile(environment);
        if (ephemeral) {
            log.warn("NULLNULL_DELETION_TOKEN_SECRET is empty — minting an ephemeral key for this "
                    + "process; deletion status tokens stop verifying after a restart");
        }
        this.tokens = new DeletionTokens(ephemeral ? ephemeralSecret() : secret);
    }

    /**
     * This class already named the environment variable rather than the property it binds, which is
     * why the pattern was copied elsewhere. What it did not do was catch the parse: an EMPTY value
     * died one line earlier, inside {@code Duration.parse}, as {@code "Text cannot be parsed to a
     * Duration"} - a message that names neither the variable nor which of the two durations failed.
     * An empty environment variable is a value, not a missing one, so it arrives here rather than
     * falling back to the default declared in application.yaml.
     */
    private static Duration positive(String name, String raw) {
        Duration value;
        try {
            value = Duration.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException unparseable) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration but was '"
                    + raw + "'; an empty value is a value and does not fall back to the documented"
                    + " default (docs/operations/ENVIRONMENT.md §3)", unparseable);
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration but was '"
                    + raw + "' (docs/operations/ENVIRONMENT.md §3)");
        }
        return value;
    }

    /**
     * Taken as text for the same reason {@code APP_COOKIE_SECURE} is: while this was a primitive
     * {@code int}, Spring's converter refused an empty value before any code here ran, so the range
     * check below - which did name the variable - could not be reached, and the case could not be
     * written as a unit test because this class is constructed directly.
     */
    private static int count(String name, String raw, int minimum, int maximum) {
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException unparseable) {
            throw new IllegalArgumentException(name + " must be a whole number between " + minimum
                    + " and " + maximum + " but was '" + raw + "'; an empty value is a value and does"
                    + " not fall back to the documented default (docs/operations/ENVIRONMENT.md §3)",
                    unparseable);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum
                    + " but was " + value + " (docs/operations/ENVIRONMENT.md §3)");
        }
        return value;
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
