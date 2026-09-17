package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SessionPropertiesTest {
    private SessionProperties props(String profile, String secure, String ttl, String origin, String domain) {
        var env=new MockEnvironment();env.setActiveProfiles(profile);
        return new SessionProperties(ttl,"P90D","PT2H","PT1M",secure,domain,origin,env);
    }
    @Test void secureCookiesOnlyOutsideLocal() {
        for(String profile:new String[]{"test","integration","production"}) {
            assertThatThrownBy(() -> props(profile,"false","P30D","https://example.test", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(props(profile,"true","P30D","https://example.test", "").cookieName()).isEqualTo("__Host-nullnull_session");
        }
        assertThat(props("local","false","P30D","http://localhost:5173", "").cookieName()).isEqualTo("nullnull_session");
        assertThatThrownBy(() -> props("local","true","P30D","http://localhost:5173", "example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        var env=new MockEnvironment();env.setActiveProfiles("local","production");
        assertThatThrownBy(() -> new SessionProperties("P30D","P90D","PT2H","PT1M","false","","http://localhost",env))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An empty value is a VALUE, and this is the one constructor argument where that costs security.
     *
     * <p>{@code .env.example} ships {@code APP_COOKIE_SECURE} blank and a shell that exports the file
     * wholesale turns that blank into a real, empty environment variable. {@code ${APP_COOKIE_SECURE:true}}
     * applies its default only when the variable is ABSENT - an empty one is present and wins - so an
     * empty string is what arrives here.
     *
     * <p>Both halves matter. Under the local profile a silent {@code false} passes every guard in this
     * class and changes {@code cookieName()} from {@code __Host-} to the unprefixed form, which is the
     * failure nobody would see. Outside it the guard does fire, but on the wrong subject: the operator
     * is told about profiles when what they set was a variable.
     */
    @Test void anEmptyCookieSecureIsRefusedByName() {
        assertThatThrownBy(() -> props("local","","P30D","http://localhost:5173", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP_COOKIE_SECURE");
        assertThatThrownBy(() -> props("production","","P30D","https://example.test", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APP_COOKIE_SECURE");
    }

    /**
     * The three session durations refuse a bad value but do not say which one they refused.
     *
     * <p>{@code positive} here takes only the value, so every one of them fails with the same
     * sentence - and an empty value does not even reach it, because {@code Duration.parse("")} throws
     * first with a message about text that cannot be parsed. An operator who exported a blank
     * {@code APP_SESSION_TTL} is told neither the variable they set nor which of the four durations
     * was the problem. {@code DeletionProperties} solved this two slices ago by passing the
     * environment-variable name into its own {@code positive}; this class never did.
     */
    @Test void aBadDurationIsRefusedByName() {
        assertThatThrownBy(() -> props("test","true","","https://example.test", ""))
                .hasMessageContaining("APP_SESSION_TTL");
        assertThatThrownBy(() -> props("test","true","PT0S","https://example.test", ""))
                .hasMessageContaining("APP_SESSION_TTL");
    }

    @Test void durationsAndOriginFailClosed() {
        for(String ttl:new String[]{"30","PT0S","-P1D","PT30S"}) {
            assertThatThrownBy(() -> props("test","true",ttl,"http://localhost:5173", "")).isInstanceOf(RuntimeException.class);
        }
        for(String origin:new String[]{"null","ftp://example.test","https://user@example.test","https://example.test/path",
                "https://example.test?query","https://example.test#fragment"}) {
            assertThatThrownBy(() -> props("test","true","P30D",origin, "")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
