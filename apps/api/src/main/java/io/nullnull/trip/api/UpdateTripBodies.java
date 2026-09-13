package io.nullnull.trip.api;

import io.nullnull.trip.api.TripController.SetConstraintBody;
import io.nullnull.trip.application.UpdateTripCommand;
import io.nullnull.trip.application.UpdateTripItemCommand;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.ConstraintSource;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripStatus;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wire shapes that need the merge-patch distinction between "absent" and "null", and the
 * discriminated constraint union.
 *
 * <p>The patch arrives as a raw map rather than a record because a record cannot tell an omitted
 * field from one sent as null - both arrive as null - and for merge-patch that is the entire
 * semantic. `Map.containsKey` is the only thing that carries it.
 */
final class UpdateTripBodies {

    private static final Set<String> KNOWN = Set.of("title", "startDate", "endDate", "timezone",
            "planningLevel", "status");

    private static final Set<String> KNOWN_ITEM = Set.of("date", "position", "startTime",
            "durationMinutes", "note", "releaseConstraints");

    private UpdateTripBodies() {
    }

    static UpdateTripCommand from(Map<String, Object> patch) {
        if (patch == null) {
            throw new TripValidationException("body", "NotNull", "a merge patch body is required");
        }
        for (String key : patch.keySet()) {
            if (!KNOWN.contains(key)) {
                // The contract's schemas are closed, so an unknown field is a rejected request
                // rather than one silently ignored - a typo'd field name must not read as success.
                throw new TripValidationException(key, "Unknown", "the patch carries an unknown field");
            }
        }
        return new UpdateTripCommand(
                optional(patch, "title", UpdateTripBodies::text),
                optional(patch, "startDate", UpdateTripBodies::date),
                optional(patch, "endDate", UpdateTripBodies::date),
                optional(patch, "timezone", UpdateTripBodies::text),
                optional(patch, "planningLevel", value -> planningLevel(text(value, "planningLevel")),
                        "planningLevel"),
                optional(patch, "status", value -> status(text(value, "status")), "status"));
    }

    /**
     * updateTripItem's patch. Three of its fields are nullable, so they are read as
     * {@code Optional<Optional<T>>}: absent outer means the caller did not send the field, empty
     * inner means they sent null to clear it.
     */
    static UpdateTripItemCommand item(Map<String, Object> patch) {
        if (patch == null) {
            throw new TripValidationException("body", "NotNull", "a merge patch body is required");
        }
        for (String key : patch.keySet()) {
            if (!KNOWN_ITEM.contains(key)) {
                throw new TripValidationException(key, "Unknown", "the patch carries an unknown field");
            }
        }
        return new UpdateTripItemCommand(
                optional(patch, "date", UpdateTripBodies::date),
                optional(patch, "position", value -> integer(value, "position"), "position"),
                nullable(patch, "startTime", value -> time(value, "startTime")),
                nullable(patch, "durationMinutes", value -> integer(value, "durationMinutes")),
                nullable(patch, "note", value -> text(value, "note")),
                releasedLocks(patch));
    }

    /** A field the contract declares nullable: absent, a value, or an explicit null that clears it. */
    private static <T> Optional<Optional<T>> nullable(Map<String, Object> patch, String key,
            java.util.function.Function<Object, T> reader) {
        if (!patch.containsKey(key)) {
            return Optional.empty();
        }
        Object value = patch.get(key);
        return Optional.of(value == null ? Optional.empty() : Optional.of(reader.apply(value)));
    }

    private static Set<LockType> releasedLocks(Map<String, Object> patch) {
        if (!patch.containsKey("releaseConstraints")) {
            return Set.of();
        }
        Object value = patch.get("releaseConstraints");
        if (!(value instanceof java.util.List<?> names)) {
            throw new TripValidationException("releaseConstraints", "Type", "expected an array");
        }
        Set<LockType> released = new java.util.LinkedHashSet<>();
        for (Object name : names) {
            try {
                released.add(LockType.valueOf(text(name, "releaseConstraints")));
            } catch (IllegalArgumentException unknown) {
                throw new TripValidationException("releaseConstraints", "Enum",
                        "releaseConstraints must name a lock type");
            }
        }
        return released;
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new TripValidationException(field, "Type", "expected an integer");
        }
        return number.intValue();
    }

    private static LocalTime time(Object value, String field) {
        try {
            return LocalTime.parse(text(value, field));
        } catch (DateTimeParseException malformed) {
            throw new TripValidationException(field, "Format", "expected HH:mm:ss");
        }
    }

    /** Builds one lock from the flattened union body, checking the fields its type actually owns. */
    static TripConstraint constraint(SetConstraintBody body) {
        if (body == null || body.type() == null) {
            throw new TripValidationException("constraints[].type", "NotNull", "type is required");
        }
        if (!Boolean.TRUE.equals(body.locked())) {
            // The contract declares locked as const true for all four: a lock exists or its row does
            // not. "locked: false" is a release, which is removeTripItemConstraint, not this.
            throw new TripValidationException("constraints[].locked", "Const",
                    "locked must be true; releasing a lock is a separate operation");
        }
        ConstraintSource source = ConstraintSource.of(body.source() == null ? "USER" : body.source());
        ItemLock lock = switch (body.type()) {
            case "MUST_VISIT" -> new ItemLock.MustVisit();
            case "DATE" -> new ItemLock.Date(required(body.date(), "constraints[].date"));
            case "TIME" -> new ItemLock.Time(required(body.startTime(), "constraints[].startTime"),
                    body.toleranceMinutes() == null ? 0 : body.toleranceMinutes());
            case "RESERVATION" -> new ItemLock.Reservation(
                    required(body.date(), "constraints[].date"),
                    required(body.startTime(), "constraints[].startTime"), body.endTime());
            default -> throw new TripValidationException("constraints[].type", "Enum",
                    "type must be MUST_VISIT, DATE, TIME or RESERVATION");
        };
        return new TripConstraint(lock, source);
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new TripValidationException(field, "NotNull", "this constraint type requires it");
        }
        return value;
    }

    private static <T> Optional<T> optional(Map<String, Object> patch, String key,
            java.util.function.BiFunction<Object, String, T> reader) {
        return optional(patch, key, value -> reader.apply(value, key), key);
    }

    private static <T> Optional<T> optional(Map<String, Object> patch, String key,
            java.util.function.Function<Object, T> reader, String field) {
        if (!patch.containsKey(key)) {
            return Optional.empty();
        }
        Object value = patch.get(key);
        if (value == null) {
            // None of these fields is nullable, so an explicit null is a request to clear something
            // that cannot be cleared - reported as a field error rather than treated as absent.
            throw new TripValidationException(field, "NotNull", "this field cannot be set to null");
        }
        return Optional.of(reader.apply(value));
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String string)) {
            throw new TripValidationException(field, "Type", "expected a string");
        }
        return string;
    }

    private static LocalDate date(Object value, String field) {
        try {
            return LocalDate.parse(text(value, field));
        } catch (DateTimeParseException invalid) {
            throw new TripValidationException(field, "Format", "expected an ISO 8601 date");
        }
    }

    private static PlanningLevel planningLevel(String value) {
        try {
            return PlanningLevel.of(value);
        } catch (IllegalArgumentException unknown) {
            throw new TripValidationException("planningLevel", "Enum",
                    "planningLevel must be NOTHING, MUST_VISIT_ONLY or MOSTLY_PLANNED");
        }
    }

    private static TripStatus status(String value) {
        try {
            return TripStatus.of(value);
        } catch (IllegalArgumentException unknown) {
            throw new TripValidationException("status", "Enum",
                    "status must be DRAFT, ACTIVE or ARCHIVED");
        }
    }
}
