package io.nullnull.live.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic cross-replica claim; the connection is returned before the provider request starts. */
@Repository
public class JdbcSeoulLiveRefreshClaim {

    private final JdbcTemplate jdbc;

    public JdbcSeoulLiveRefreshClaim(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean claim(String areaName) {
        if (areaName == null || areaName.isBlank() || areaName.length() > 200) {
            throw new IllegalArgumentException("a reviewed Seoul area name is required");
        }
        Integer claimed = jdbc.queryForObject("""
                WITH claimed AS (
                    INSERT INTO seoul_live_refresh_claims (source_code, area_name, next_due_at)
                    VALUES ('SEOUL_CITYDATA', ?, clock_timestamp() + interval '5 minutes')
                    ON CONFLICT (source_code, area_name) DO UPDATE
                       SET next_due_at = EXCLUDED.next_due_at
                     WHERE seoul_live_refresh_claims.next_due_at <= clock_timestamp()
                    RETURNING 1
                ) SELECT count(*) FROM claimed
                """, Integer.class, areaName);
        return claimed != null && claimed == 1;
    }
}
