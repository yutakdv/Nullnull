package io.nullnull.social.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an operator asks the curation script to publish, as it is written in the input file.
 *
 * <p>A-031 settled that P0's curated feed posts are made by an operations script rather than by a
 * migration or a writing endpoint. This is that script's input, and it is a plan rather than a
 * command: every id in it is chosen by the operator and stable, so running the same file twice is
 * the same request and not a second one.
 *
 * <p>What it deliberately cannot say is as important as what it can. There is no place definition
 * here - only a reference to one - because a curated post must not be able to invent a catalog row
 * with no provenance behind it. There is no licence either: A-024 made a cover a 1st-party asset and
 * V021 seeded the one licence that describes it, so the plan names an image and the importer hangs it
 * off the licence the product actually has.
 */
public record CuratedPostPlan(List<CuratedPost> posts) {

    public CuratedPostPlan {
        posts = List.copyOf(Objects.requireNonNull(posts, "posts"));
        if (posts.isEmpty()) {
            throw new CurationException("the plan lists no posts");
        }
        if (posts.stream().map(CuratedPost::id).distinct().count() != posts.size()) {
            throw new CurationException("two posts in the plan share an id");
        }
    }

    /**
     * One post, with the places it mentions.
     *
     * <p>{@code publishedAt} is the operator's, not the clock's: a curated feed is ordered by it, so
     * the order of the feed is something the file states rather than something the run time decides.
     * Two runs of the same file therefore produce the same feed.
     */
    public record CuratedPost(UUID id, String title, String body, Instant publishedAt,
            CuratedCover cover, List<CuratedPlace> places) {

        public CuratedPost {
            Objects.requireNonNull(id, "id");
            requireText(title, "title", 200);
            requireText(body, "body", 20000);
            Objects.requireNonNull(publishedAt, "publishedAt");
            Objects.requireNonNull(cover, "cover");
            places = List.copyOf(Objects.requireNonNull(places, "places"));
            if (places.isEmpty()) {
                // V022 refuses a published post with no primary place, and the feed answers 503 for
                // the whole page when one slips through. Caught here so the file is rejected before
                // anything is written rather than by a trigger halfway down it.
                throw new CurationException("post " + id + " names no place");
            }
            if (places.stream().filter(CuratedPlace::primary).count() != 1) {
                throw new CurationException("post " + id + " must name exactly one PRIMARY place");
            }
            if (places.stream().map(CuratedPlace::placeId).distinct().count() != places.size()) {
                throw new CurationException("post " + id + " names the same place twice");
            }
        }
    }

    /** A 1st-party image. The checksum is the file's, so the same image cannot be stored twice. */
    public record CuratedCover(String url, String alt, String checksum) {

        private static final Pattern HTTPS = Pattern.compile("^https://[^\\s]+$");
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public CuratedCover {
            requireText(url, "cover.url", 2000);
            if (!HTTPS.matcher(url).matches()) {
                throw new CurationException("cover url must be https");
            }
            requireText(alt, "cover.alt", 500);
            if (checksum == null || !SHA256.matcher(checksum).matches()) {
                // media_assets requires the shape; requiring it here means a typo is a rejected file
                // and not a driver error halfway through the import.
                throw new CurationException("cover checksum must be 64 lowercase hex characters");
            }
        }
    }

    /** A reference to a catalog place the importer must find; it never creates one. */
    public record CuratedPlace(UUID placeId, boolean primary) {
        public CuratedPlace {
            Objects.requireNonNull(placeId, "placeId");
        }
    }

    private static void requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new CurationException(field + " is required");
        }
        if (value.length() > max) {
            throw new CurationException(field + " is longer than " + max + " characters");
        }
    }

    /** A plan the importer refuses. The message names the field, never the value. */
    public static final class CurationException extends RuntimeException {
        public CurationException(String message) {
            super(message);
        }
    }
}
