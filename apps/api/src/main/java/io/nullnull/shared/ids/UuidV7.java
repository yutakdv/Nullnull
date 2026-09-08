package io.nullnull.shared.ids;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Application-generated UUID version 7 (RFC 9562): 48-bit Unix millisecond timestamp,
 * version nibble 7, 74 random bits, RFC 4122 variant. Time-ordered for index locality
 * (docs/architecture/ERD.md recommends application-generated UUID v7).
 */
public final class UuidV7 {

    private static final RandomGenerator SECURE_RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID create(Clock clock) {
        return create(clock.millis(), SECURE_RANDOM);
    }

    public static UUID create(long epochMillis, RandomGenerator random) {
        if (epochMillis < 0 || epochMillis > 0xFFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("epochMillis out of UUIDv7 48-bit range: " + epochMillis);
        }
        long randA = random.nextLong() & 0x0FFFL;
        long msb = (epochMillis << 16) | 0x7000L | randA;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** Millisecond timestamp encoded in a version-7 UUID. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
