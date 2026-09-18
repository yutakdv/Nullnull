package io.nullnull.shared.problem;

/**
 * What a 401 request did not carry at all (#240 A-1). The browser cannot see an httpOnly cookie, so a first visit
 * and an expired session looked identical to the client; this is the one bit that tells them apart.
 *
 * <p>One value, deliberately. Every other session failure - expired, revoked, forged, never issued, malformed -
 * answers a plain UNAUTHORIZED with no field, because naming any of them would tell the caller whether a cookie was
 * once valid. Adding a second value here is exactly that disclosure, so it has to be a code change here AND an
 * {@code enum} change in the contract, which oasdiff reports as a breaking error.
 */
public enum MissingCredential {
    SESSION_COOKIE
}
