package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The three deletion settings refuse a bad value, and until now they refused an EMPTY one somewhere
 * the operator could not see.
 *
 * <p>{@code positive(name, raw)} already carries the environment-variable name - this class invented
 * that pattern - but it calls {@code Duration.parse} without catching it, so an empty value dies one
 * line earlier with {@code "Text cannot be parsed to a Duration"} and the name never appears. The
 * retry limit fails even sooner: it was a primitive {@code int}, so Spring's converter refused the
 * empty string before any code here ran, and the range check below it - which does name the
 * variable - could not be reached.
 *
 * <p>There was no test for this class at all; only {@code DeletionTokensTest} for the token helper.
 * These cases are all object-level refusals, so they are unit tests: nothing here starts a context.
 */
class DeletionPropertiesTest {

    private DeletionProperties props(String statusTtl, String tombstoneRetention, String retryLimit) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new DeletionProperties(statusTtl, tombstoneRetention, retryLimit, "", environment);
    }

    @Test void aValidConfigurationIsAccepted() {
        DeletionProperties properties = props("P7D", "P21D", "5");
        assertThat(properties.statusTtl).hasDays(7);
        assertThat(properties.tombstoneRetention).hasDays(21);
        assertThat(properties.retryLimit).isEqualTo(5);
    }

    /**
     * {@code .env.example} ships all three blank, and a shell that exports the file wholesale turns
     * each blank into a real, empty environment variable. {@code ${...}} applies a default only when
     * the variable is ABSENT, so the empty value is what arrives.
     */
    @Test void anEmptyDurationIsRefusedByName() {
        assertThatThrownBy(() -> props("", "P21D", "5"))
                .hasMessageContaining("APP_DELETION_STATUS_TOKEN_TTL");
        assertThatThrownBy(() -> props("P7D", "", "5"))
                .hasMessageContaining("APP_DELETION_TOMBSTONE_RETENTION");
    }

    @Test void anEmptyRetryLimitIsRefusedByName() {
        assertThatThrownBy(() -> props("P7D", "P21D", ""))
                .hasMessageContaining("APP_DELETION_RETRY_LIMIT");
    }

    /** A value that parses but is out of range was already named; this pins that it stays named. */
    @Test void anOutOfRangeRetryLimitIsRefusedByName() {
        for (String limit : new String[] {"0", "21", "-1"}) {
            assertThatThrownBy(() -> props("P7D", "P21D", limit))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("APP_DELETION_RETRY_LIMIT");
        }
    }

    /** Same for a duration that parses but is not positive. */
    @Test void aNonPositiveDurationIsRefusedByName() {
        assertThatThrownBy(() -> props("PT0S", "P21D", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP_DELETION_STATUS_TOKEN_TTL");
    }

    /**
     * The one cross-field rule: a tombstone that expires before the status token would leave a
     * receipt whose bearer hash outlives the row it points at.
     */
    @Test void tombstoneRetentionMustCoverTheStatusTokenTtl() {
        assertThatThrownBy(() -> props("P21D", "P7D", "5"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
