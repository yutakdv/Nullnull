package io.nullnull.social.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BA-085: the application half of the deep link rule. */
@DisplayName("BA-085 notification deep link parsing")
class NotificationDeepLinkTest {

    @Test
    @DisplayName("BA-085-T2 a deep link carrying a scheme, host, query or fragment is refused")
    void externalAndDecoratedLinksAreRefused() {
        // Both directions in one test, because a rule that only ever sees bad input passes by
        // refusing everything - which is a different defect with the same green.
        for (String accepted : new String[] {
                "/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01",
                "/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4a20-9f11-7c08-b3d7-2e5a41c9b104",
                "/notifications",
                "/profile"}) {
            assertThatCode(() -> NotificationDeepLink.require(accepted))
                    .as("%s is an internal route", accepted).doesNotThrowAnyException();
        }

        for (String refused : new String[] {
                "https://evil.example/trip/1",     // scheme
                "http://localhost/trip/1",          // scheme, and a host this server would trust
                "evil.example/trip/1",              // bare host
                "//intranet/admin",                 // protocol-relative: a browser resolves the host
                "//localhost/x",                    // the same, with no dot to give it away
                "///triple/x",
                "/trip/1?next=/profile",            // query
                "/trip/1#section",                  // fragment
                "/trip/1\\..\\admin",               // backslash
                "/trip/1 /profile",                 // whitespace
                "/trip/1\nLocation: https://evil",  // header injection through a log or a redirect
                "/",                                // the root names no route
                ""}) {
            assertThatThrownBy(() -> NotificationDeepLink.require(refused))
                    .as("%s is not an internal route", refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> NotificationDeepLink.require(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BA-085 a deep link longer than the contract's maxLength is refused")
    void oversizedLinksAreRefused() {
        String atLimit = "/" + "a".repeat(NotificationDeepLink.MAX_LENGTH - 1);
        assertThat(atLimit).hasSize(NotificationDeepLink.MAX_LENGTH);
        assertThatCode(() -> NotificationDeepLink.require(atLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> NotificationDeepLink.require(atLimit + "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
