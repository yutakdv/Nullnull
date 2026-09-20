package io.nullnull.social.domain;

/**
 * The application-side half of the deep link rule (ERD §"notifications.deep_link", BA-085-T2).
 *
 * <p>ERD asks for a database check and an application parser. They are deliberately not the same
 * check. V037 holds what cannot move - an absolute path with no scheme, host, query or fragment -
 * because a migration cannot be edited once applied and the route allowlist is moving right now.
 * This holds the same invariant with the reasons attached, and refuses a few shapes SQL has no
 * comfortable way to name.
 *
 * <p><strong>The route allowlist is not here.</strong> Its canon is the contract's {@code deepLink}
 * pattern. Putting a copy in this class today would be a guard that can never fire: nothing
 * produces a notification yet, so the only caller is a test, and the copy would go stale against a
 * pattern that is being corrected in the same week. The slice that emits notifications is where an
 * allowlist check earns its place, because that is where a link is first minted.
 */
public final class NotificationDeepLink {

    /** Contract: Notification.deepLink maxLength. */
    public static final int MAX_LENGTH = 300;

    /** Exactly the character class in V037's {@code notifications_deep_link_check}. */
    private static final java.util.BitSet ALLOWED = new java.util.BitSet(128);

    static {
        for (char c = 'a'; c <= 'z'; c++) {
            ALLOWED.set(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            ALLOWED.set(c);
        }
        for (char c = '0'; c <= '9'; c++) {
            ALLOWED.set(c);
        }
        ALLOWED.set('/');
        ALLOWED.set('-');
        ALLOWED.set('_');
    }

    private NotificationDeepLink() {
    }

    /**
     * Returns the link, or throws if it is not an internal absolute path.
     *
     * @throws IllegalArgumentException with a reason naming the rule that refused it
     */
    public static String require(String link) {
        if (link == null || link.isEmpty()) {
            throw new IllegalArgumentException("deepLink is required");
        }
        if (link.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("deepLink must be at most " + MAX_LENGTH + " characters");
        }
        if (link.charAt(0) != '/') {
            // Anything with a scheme lands here, and so does a bare host.
            throw new IllegalArgumentException("deepLink must be an absolute path beginning with '/'");
        }
        if (link.length() < 2) {
            throw new IllegalArgumentException("deepLink must name a route, not the root");
        }
        if (link.charAt(1) == '/' || link.charAt(1) == '\\') {
            // "//host/path" satisfies every other rule here and a browser resolves it as an absolute
            // URL onto another origin. This is the escape the whole check exists to refuse, and it
            // is the one a character allowlist misses whenever the host has no dot in it.
            throw new IllegalArgumentException("deepLink must not be protocol-relative");
        }
        for (int index = 0; index < link.length(); index++) {
            char character = link.charAt(index);
            if (character == '?') {
                throw new IllegalArgumentException("deepLink must not carry a query string");
            }
            if (character == '#') {
                throw new IllegalArgumentException("deepLink must not carry a fragment");
            }
            if (character == '\\') {
                throw new IllegalArgumentException("deepLink must not contain a backslash");
            }
            // Control characters and whitespace, which a log line or an HTML attribute would treat
            // as a boundary rather than as part of the value.
            if (character <= ' ' || character == 0x7f) {
                throw new IllegalArgumentException("deepLink must not contain whitespace or control characters");
            }
            // The same character set V037's notifications_deep_link_check enforces, and that is the
            // whole point of it being here: this parser used to be WIDER than the CHECK, so
            // "/trip/a.b" and "/trip/서울" passed Java and died at the INSERT as a constraint
            // violation - a 500 where a refusal belonged. Two layers that disagree do not give you
            // two defences; they give you one defence and one way to crash.
            // NotificationDeepLinkParityIT compares the two on the same inputs, in both directions.
            if (!ALLOWED.get(character)) {
                throw new IllegalArgumentException(
                        "deepLink may contain only letters, digits, '/', '-' and '_'");
            }
        }
        return link;
    }
}
