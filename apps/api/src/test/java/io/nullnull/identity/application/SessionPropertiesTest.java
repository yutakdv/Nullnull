package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SessionPropertiesTest {
    private SessionProperties props(String profile, boolean secure, String ttl, String origin, String domain) {
        var env=new MockEnvironment();env.setActiveProfiles(profile);
        return new SessionProperties(ttl,"P90D","PT2H","PT1M",secure,domain,origin,env);
    }
    @Test void secureCookiesOnlyOutsideLocal() {
        for(String profile:new String[]{"test","integration","production"}) {
            assertThatThrownBy(() -> props(profile,false,"P30D","https://example.test", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(props(profile,true,"P30D","https://example.test", "").cookieName()).isEqualTo("__Host-nullnull_session");
        }
        assertThat(props("local",false,"P30D","http://localhost:5173", "").cookieName()).isEqualTo("nullnull_session");
        assertThatThrownBy(() -> props("local",true,"P30D","http://localhost:5173", "example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        var env=new MockEnvironment();env.setActiveProfiles("local","production");
        assertThatThrownBy(() -> new SessionProperties("P30D","P90D","PT2H","PT1M",false,"","http://localhost",env))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void durationsAndOriginFailClosed() {
        for(String ttl:new String[]{"30","PT0S","-P1D","PT30S"}) {
            assertThatThrownBy(() -> props("test",true,ttl,"http://localhost:5173", "")).isInstanceOf(RuntimeException.class);
        }
        for(String origin:new String[]{"null","ftp://example.test","https://user@example.test","https://example.test/path",
                "https://example.test?query","https://example.test#fragment"}) {
            assertThatThrownBy(() -> props("test",true,"P30D",origin, "")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
