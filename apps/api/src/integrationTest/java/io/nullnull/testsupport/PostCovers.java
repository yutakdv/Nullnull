package io.nullnull.testsupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A 1st-party cover asset, which a published post now has to name.
 *
 * <p>Tests used to publish a post with a {@code cover_url} string and nothing behind it, because
 * that was all the schema asked for. A-024 says a cover is a 1st-party asset the team made and never
 * a provider photograph, and V021 turns that into a foreign key plus a CHECK, so a published row
 * without one is refused. Fourteen cases were creating exactly the row the decision forbids; this is
 * what they use instead.
 *
 * <p>The licence is looked up rather than created: V021 seeds one NULLNULL_FIRST_PARTY licence and a
 * test that made its own would be asserting against a licence the product does not have.
 */
public final class PostCovers {

    private PostCovers() {
    }

    /** A media asset on the seeded 1st-party licence, ready to be a published post's cover. */
    public static UUID firstPartyAsset(JdbcTemplate jdbc, Instant at) {
        UUID licenceId = jdbc.queryForObject(
                "SELECT id FROM asset_licenses WHERE source_code = 'NULLNULL_FIRST_PARTY'",
                UUID.class);
        UUID assetId = UUID.randomUUID();
        jdbc.update("INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,"
                        + " served_url, checksum, media_type, alt_text, license_checked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'IMAGE', ?, ?)",
                assetId, licenceId, "cover-" + assetId,
                "https://assets.nullnull.test/covers/" + assetId + ".png",
                "https://assets.nullnull.test/covers/" + assetId + ".png",
                checksum(assetId), "표지 일러스트", Timestamp.from(at));
        return assetId;
    }

    /** Any 64 hex characters; the column requires the shape, and no test depends on the value. */
    private static String checksum(UUID seed) {
        String hex = seed.toString().replace("-", "");
        return (hex + hex).substring(0, 64);
    }
}
