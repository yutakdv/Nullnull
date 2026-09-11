package io.nullnull.operations.domain;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The {@code payload_reference} of a job: domain identifiers only.
 *
 * <p>docs/architecture/SYSTEM_ARCHITECTURE.md §19.2 and docs/architecture/ERD.md §4 both say the
 * payload carries job type and domain IDs, never raw itinerary text, precise coordinates or provider
 * secrets. That rule is enforced here rather than trusted, because a job payload is durable, is read
 * back by an operator and is the easiest place for pasted user text to leak into storage: keys and
 * values are matched against identifier shapes that no free text, coordinate pair or secret can pass
 * (no whitespace, no comma, bounded length).
 *
 * <p>The map is kept sorted so the serialised JSON is stable for the same content, which keeps a
 * stored payload comparable across enqueues and diffable in a test.
 */
public record JobPayload(Map<String, String> values) {

    /** Enough for the identifiers a job needs; a payload wanting more is carrying data, not a reference. */
    public static final int MAX_ENTRIES = 16;

    static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");

    /**
     * Identifier shape: UUIDs, opaque IDs, enum names and source codes. A decimal point is allowed
     * because some external IDs carry one; a comma, a space and every quote character are not, so a
     * latitude/longitude pair or a sentence cannot be smuggled through as one value.
     */
    static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_:.-]{1,64}");

    public JobPayload {
        if (values == null) {
            throw new IllegalArgumentException("job payload values are required");
        }
        if (values.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "job payload holds at most " + MAX_ENTRIES + " entries but had " + values.size());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !KEY.matcher(key).matches()) {
                // The key is a field name chosen in code, so naming it is safe; values never appear.
                throw new IllegalArgumentException("job payload key is not an identifier name: " + key);
            }
            if (entry.getValue() == null || !VALUE.matcher(entry.getValue()).matches()) {
                throw new IllegalArgumentException(
                        "job payload value for key " + key + " is not a domain identifier");
            }
        }
        values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public static JobPayload of(Map<String, String> values) {
        return new JobPayload(values);
    }

    public static JobPayload empty() {
        return new JobPayload(Map.of());
    }

    /** The identifier stored under {@code key}, or null when the job did not carry it. */
    public String get(String key) {
        return values.get(key);
    }
}
