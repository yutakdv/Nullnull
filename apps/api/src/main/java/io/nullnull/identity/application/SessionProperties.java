package io.nullnull.identity.application;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class SessionProperties {
    public final Duration idle;
    public final Duration absolute;
    public final Duration csrf;
    public final Duration touchInterval;
    public final boolean secure;
    public final URI origin;

    public SessionProperties(@Value("${APP_SESSION_TTL:P30D}") String idle,
            @Value("${APP_SESSION_ABSOLUTE_TTL:P90D}") String absolute,
            @Value("${APP_CSRF_TOKEN_TTL:PT2H}") String csrf,
            @Value("${nullnull.session.touch-interval:PT1M}") String touchInterval,
            @Value("${APP_COOKIE_SECURE:true}") boolean secure,
            @Value("${APP_COOKIE_DOMAIN:}") String domain,
            @Value("${APP_PUBLIC_ORIGIN:http://localhost:5173}") String origin,
            Environment environment) {
        this.idle = positive(idle);
        this.absolute = positive(absolute);
        this.csrf = positive(csrf);
        this.touchInterval = positive(touchInterval);
        this.secure = secure;
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        if ((!secure && !(profiles.length == 1 && Arrays.asList(profiles).contains("local")))
                || !domain.isEmpty()) {
            throw new IllegalArgumentException("Insecure cookies require only the local profile; Domain is forbidden.");
        }
        this.origin = URI.create(origin);
        if (!("https".equals(this.origin.getScheme()) || "http".equals(this.origin.getScheme()))
                || this.origin.getHost() == null || this.origin.getRawUserInfo() != null
                || this.origin.getRawQuery() != null || this.origin.getRawFragment() != null
                || !(this.origin.getRawPath().isEmpty() || this.origin.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("APP_PUBLIC_ORIGIN must be a single HTTP origin.");
        }
        if (this.touchInterval.compareTo(this.idle) >= 0) {
            throw new IllegalArgumentException("Session touch interval must be shorter than idle TTL.");
        }
    }
    private static Duration positive(String value) {
        Duration duration = Duration.parse(value);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Session durations must be positive ISO-8601 values.");
        }
        return duration;
    }
    public String cookieName() { return secure ? "__Host-nullnull_session" : "nullnull_session"; }
}
