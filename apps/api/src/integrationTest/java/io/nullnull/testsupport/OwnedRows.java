package io.nullnull.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes the rows one test class created, and everything hanging off them.
 *
 * <p>A blanket {@code DELETE FROM places} is the obvious cleanup and it is wrong here. Every
 * {@code @SpringBootTest} configuration gets its own container locally, but the required gate runs
 * every context against ONE database ({@code NULLNULL_TEST_DATABASE=external}), where that
 * statement either takes another class's rows with it or - far more often - fails: a dozen tables
 * reference {@code places} WITHOUT cascade, deliberately, because a place must not vanish from
 * under a candidate that points at it. The class that gets the error is then not the one that left
 * a row, it is the one trying to clear the table, and local green cannot see any of it
 * (AGENTS.md rule 6).
 *
 * <p><strong>The referencing tables are read from the database, not listed here.</strong> A list
 * written today goes stale the day a migration adds another table, and it goes stale silently -
 * cleanup starts failing in a class nobody touched. {@code pg_constraint} already knows the answer
 * and cannot be out of date.
 *
 * <p>It follows grandchildren too, because not everything cascades:
 * {@code place_hours_windows.observation_id} has no {@code ON DELETE CASCADE}, so deleting the
 * observations a place owns fails while their windows are still there. Recursion stops at tables
 * with no single {@code id} column - a join table has nothing pointing at it to follow.
 */
public final class OwnedRows {

    private static final int MAX_DEPTH = 4;

    private OwnedRows() {
    }

    /** Deletes these rows of {@code table} and everything hanging off them. Empty does nothing. */
    public static void remove(JdbcTemplate jdbc, String table, List<UUID> ids) {
        deleteBy(jdbc, table, "id", ids, 0);
    }

    /** Ids of {@code table} that were not there when {@code before} was taken. */
    public static List<UUID> appeared(JdbcTemplate jdbc, String table, List<UUID> before) {
        List<UUID> now = new ArrayList<>(jdbc.queryForList("SELECT id FROM " + table, UUID.class));
        now.removeAll(before);
        return now;
    }

    /** Ids of {@code table} as they are right now, to be passed back to {@link #appeared}. */
    public static List<UUID> snapshot(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT id FROM " + table, UUID.class);
    }

    private static void deleteBy(JdbcTemplate jdbc, String table, String column, List<UUID> values, int depth) {
        if (values.isEmpty() || depth > MAX_DEPTH) {
            return;
        }
        String placeholders = values.stream().map(value -> "?").collect(Collectors.joining(", "));
        Object[] parameters = values.toArray();
        if (hasIdColumn(jdbc, table)) {
            List<UUID> rows = jdbc.queryForList("SELECT id FROM " + table + " WHERE " + column
                    + " IN (" + placeholders + ")", UUID.class, parameters);
            for (Reference child : childrenOf(jdbc, table)) {
                deleteBy(jdbc, child.table(), child.column(), rows, depth + 1);
            }
        }
        // One statement for the whole set: places point at each other (a deprecated row names its
        // canonical one), so deleting them one at a time would fail on whichever went first.
        jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + placeholders + ")", parameters);
    }

    private record Reference(String table, String column) { }

    private static List<Reference> childrenOf(JdbcTemplate jdbc, String table) {
        return jdbc.query("""
                SELECT child.relname AS table_name, column_.attname AS column_name
                  FROM pg_constraint reference
                  JOIN pg_class child ON child.oid = reference.conrelid
                  JOIN pg_class parent ON parent.oid = reference.confrelid
                  JOIN pg_attribute column_ ON column_.attrelid = reference.conrelid
                                           AND column_.attnum = reference.conkey[1]
                 WHERE reference.contype = 'f'
                   AND parent.relname = ?
                   AND child.relname <> ?
                """, (row, index) -> new Reference(row.getString("table_name"), row.getString("column_name")),
                table, table);
    }

    private static boolean hasIdColumn(JdbcTemplate jdbc, String table) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'
                """, Integer.class, table);
        return found != null && found > 0;
    }
}
