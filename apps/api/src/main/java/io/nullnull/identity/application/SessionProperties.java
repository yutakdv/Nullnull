package io.nullnull.identity.application;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
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
            @Value("${APP_COOKIE_SECURE:true}") String secure,
            @Value("${APP_COOKIE_DOMAIN:}") String domain,
            @Value("${APP_PUBLIC_ORIGIN:http://localhost:5173}") String origin,
            Environment environment) {
        // Each name is the one the operator actually sets. Three are environment variables; the
        // fourth is a property, because a map-style key cannot be spelled as an environment variable
        // (ENVIRONMENT.md §3 documents it under that name). Passing one shared label would be the
        // same failure as the old message: true about the class, useless to the person reading it.
        this.idle = positive("APP_SESSION_TTL", idle);
        this.absolute = positive("APP_SESSION_ABSOLUTE_TTL", absolute);
        this.csrf = positive("APP_CSRF_TOKEN_TTL", csrf);
        this.touchInterval = positive("nullnull.session.touch-interval", touchInterval);
        this.secure = flag("APP_COOKIE_SECURE", secure);
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        if ((!this.secure && !(profiles.length == 1 && Arrays.asList(profiles).contains("local")))
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
    /**
     * Takes the flag as text so that an empty value can be refused <em>by name</em>.
     *
     * <p>An empty environment variable is a value, not a missing one: {@code ${APP_COOKIE_SECURE:true}}
     * applies its default only when the variable is ABSENT. While this parameter was a primitive
     * {@code boolean}, Spring's converter refused the empty string before any code here ran, and it
     * refused it as <em>"constructor parameter 4"</em> - naming neither the variable the operator set
     * nor anything they could grep for. That also made the case untestable in this class's style,
     * which constructs the object directly and so never crosses the converter at all.
     *
     * <p>Refusing rather than defaulting is the point. Falling back to the declared default here would
     * rebuild, by hand and one site at a time, the environment-wide filter this work already withdrew:
     * the same semantics at a smaller scale. What an empty value must not do is become {@code false}
     * quietly - under the local profile that passes every guard below and only changes
     * {@link #cookieName()} from the {@code __Host-} form to the unprefixed one.
     *
     * <p>Naming the variable rather than the property follows {@code DeletionProperties}, which reads
     * {@code nullnull.deletion.*} but reports {@code APP_DELETION_*}, and the message shape follows
     * {@code AccessLogFilter}: the name, the accepted set, the value received, and why it cannot stand.
     */
    private static boolean flag(String name, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return value.equalsIgnoreCase("true");
        }
        throw new IllegalArgumentException(name + " must be 'true' or 'false' but was '" + raw
                + "'; an empty value is a value and does not fall back to the documented default"
                + " (docs/operations/ENVIRONMENT.md §3)");
    }

    /**
     * Refuses a duration <em>by name</em>, the way {@code DeletionProperties} already does.
     *
     * <p>This used to take only the value, so all four durations failed with one sentence that named
     * none of them - and an empty value never even reached the check, because {@code Duration.parse("")}
     * throws first with {@code "Text cannot be parsed to a Duration"}. An operator who exported a blank
     * {@code APP_SESSION_TTL} learned neither which variable nor which of the four was at fault. The
     * parse exception is caught here for exactly that reason: the unparseable case is the one an empty
     * value takes, and it is the case most likely to be an operator's mistake rather than a typo.
     */
    private static Duration positive(String name, String raw) {
        Duration duration;
        try {
            duration = Duration.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException unparseable) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration but was '"
                    + raw + "'; an empty value is a value and does not fall back to the documented"
                    + " default (docs/operations/ENVIRONMENT.md §3)", unparseable);
        }
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive ISO-8601 duration but was '"
                    + raw + "' (docs/operations/ENVIRONMENT.md §3)");
        }
        return duration;
    }
    public String cookieName() { return secure ? "__Host-nullnull_session" : "nullnull_session"; }
}
