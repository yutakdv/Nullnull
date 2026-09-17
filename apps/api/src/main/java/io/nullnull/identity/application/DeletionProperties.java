package io.nullnull.identity.application;

import io.nullnull.operations.domain.JobRequest;
import java.security.SecureRandom;
import java.time.Duration;
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
            @Value("${nullnull.deletion.retry-limit}") int retryLimit,
            @Value("${NULLNULL_DELETION_TOKEN_SECRET:}") String secret,
            Environment environment) {
        this.statusTtl = positive("APP_DELETION_STATUS_TOKEN_TTL", statusTtl);
        this.tombstoneRetention = positive("APP_DELETION_TOMBSTONE_RETENTION", tombstoneRetention);
        if (this.tombstoneRetention.compareTo(this.statusTtl) < 0) {
            throw new IllegalArgumentException("Deletion tombstone retention must cover status token TTL.");
        }
        if (retryLimit < 1 || retryLimit > JobRequest.MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("APP_DELETION_RETRY_LIMIT must be between 1 and 20.");
        }
        this.retryLimit = retryLimit;
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

    private static Duration positive(String name, String raw) {
        Duration value = Duration.parse(raw);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration.");
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
