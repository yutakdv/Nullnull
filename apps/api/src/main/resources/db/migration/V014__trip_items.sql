-- BA-030 Phase B / BA-031: the scheduled half of the trip aggregate.
--
-- V013 deliberately stopped short of these tables because SeedTripItem.startTime had no agreed wire
-- format: the contract declared `format: time`, which requires a UTC offset, while this column
-- cannot store one and docs/api/README.md calls the field offset-less local time. That is settled
-- (#145): wall-clock fields now carry an explicit HH:mm:ss pattern, so `time` is the right column
-- and a value can no longer arrive with an offset that would be silently dropped.
CREATE TABLE trip_items (
    id uuid PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES places(id),
    trip_date date NOT NULL,
    position int NOT NULL,
    start_time time,
    duration_minutes int,
    note varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT trip_items_position_check CHECK (position >= 0),
    CONSTRAINT trip_items_duration_check CHECK (duration_minutes IS NULL OR duration_minutes >= 1),
    CONSTRAINT trip_items_timestamps_check CHECK (updated_at >= created_at),
    -- ERD §11: one item per slot. Two items claiming the same position on a day is an ordering the
    -- UI cannot render deterministically, so it is refused rather than resolved by insertion order.
    CONSTRAINT trip_items_slot_unique UNIQUE (trip_id, trip_date, position)
);
CREATE INDEX trip_items_day_idx ON trip_items (trip_id, trip_date, position);

-- ERD §11: an item's date must lie inside its trip's range. A CHECK cannot read another table, so
-- this is a trigger. It is not belt-and-braces for the application check - a date range shrink and
-- an item insert can interleave, and only the database sees both rows at once.
CREATE FUNCTION trip_items_require_date_in_range()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    range_start date;
    range_end date;
BEGIN
    SELECT start_date, end_date INTO range_start, range_end FROM trips WHERE id = NEW.trip_id;
    IF NEW.trip_date < range_start OR NEW.trip_date > range_end THEN
        RAISE EXCEPTION 'trip_items.trip_date must lie within the trip date range';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trip_items_date_in_range
    BEFORE INSERT OR UPDATE OF trip_date, trip_id ON trip_items
    FOR EACH ROW
    EXECUTE FUNCTION trip_items_require_date_in_range();

-- ERD §11: unique (trip_item_id, type); only locked=true rows are stored and releasing a lock is
-- deleting the row, so there is no "locked" column to disagree with the row's existence.
-- The typed check is ERD §11 verbatim: MUST_VISIT carries no values, DATE only date_value, TIME
-- start_time_value with a tolerance, RESERVATION date_value and start_time_value.
CREATE TABLE trip_constraints (
    id uuid PRIMARY KEY,
    trip_id uuid NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    trip_item_id uuid NOT NULL REFERENCES trip_items(id) ON DELETE CASCADE,
    type varchar(20) NOT NULL,
    source varchar(20) NOT NULL,
    date_value date,
    start_time_value time,
    end_time_value time,
    tolerance_minutes int,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT trip_constraints_type_check CHECK
        (type IN ('MUST_VISIT', 'DATE', 'TIME', 'RESERVATION')),
    CONSTRAINT trip_constraints_source_check CHECK (source IN ('USER', 'IMPORT')),
    CONSTRAINT trip_constraints_timestamps_check CHECK (updated_at >= created_at),
    CONSTRAINT trip_constraints_shape_check CHECK (
        (type = 'MUST_VISIT' AND date_value IS NULL AND start_time_value IS NULL
             AND end_time_value IS NULL AND tolerance_minutes IS NULL)
        OR (type = 'DATE' AND date_value IS NOT NULL AND start_time_value IS NULL
             AND end_time_value IS NULL AND tolerance_minutes IS NULL)
        OR (type = 'TIME' AND date_value IS NULL AND start_time_value IS NOT NULL
             AND end_time_value IS NULL AND tolerance_minutes BETWEEN 0 AND 180)
        OR (type = 'RESERVATION' AND date_value IS NOT NULL AND start_time_value IS NOT NULL
             AND tolerance_minutes IS NULL
             AND (end_time_value IS NULL OR end_time_value >= start_time_value))
    ),
    CONSTRAINT trip_constraints_one_per_type UNIQUE (trip_item_id, type)
);
CREATE INDEX trip_constraints_trip_idx ON trip_constraints (trip_id, type);

-- ERD §11: trip_id must match the item's trip. Two tables again, so a trigger.
CREATE FUNCTION trip_constraints_require_same_trip()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM trip_items WHERE id = NEW.trip_item_id AND trip_id = NEW.trip_id) THEN
        RAISE EXCEPTION 'trip_constraints.trip_id must match the constrained item trip';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trip_constraints_same_trip
    BEFORE INSERT OR UPDATE OF trip_id, trip_item_id ON trip_constraints
    FOR EACH ROW
    EXECUTE FUNCTION trip_constraints_require_same_trip();
