package io.nullnull.social.application;

import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.social.application.CuratedPostPlan.CuratedPlace;
import io.nullnull.social.application.CuratedPostPlan.CuratedPost;
import io.nullnull.social.application.CuratedPostPlan.CurationException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes the curated feed posts an operator wrote in a plan file (A-031).
 *
 * <p>P0's feed is curated, and the owner settled how: not a migration, which would make editorial
 * content part of the schema and un-editable without another one, and not a writing endpoint, which
 * P0 does not have and FEATURE_POST_CREATION keeps off. A script that reads a file is the third
 * thing, and it is the one where the input is reviewable before it runs.
 *
 * <p>Three rules this enforces that the database enforces too, checked here so a bad file is refused
 * whole rather than halfway:
 *
 * <ul>
 * <li>A place must already exist in the catalog. The importer never creates one - a curated post
 *     that could invent a place would be editorial content with no provenance, which is the thing
 *     invariant 9 and the source registry exist to prevent.</li>
 * <li>A cover hangs off the licence the product actually has (A-024's NULLNULL_FIRST_PARTY, seeded by
 *     V021). The importer looks it up and never creates one, for the reason PostCovers gives: a run
 *     that made its own licence would be publishing under a grant nobody reviewed.</li>
 * <li>The post is written as a DRAFT, given its places, and only then published. That is the order
 *     V022's trigger requires and the order a curator works in - the post exists, then its place,
 *     then it is visible.</li>
 * </ul>
 *
 * <p>Re-running the same file changes nothing. A post id that is already present is reported as
 * unchanged rather than rewritten: an operations script that silently republished edited content
 * would make the file and the feed disagree without saying so, and deciding what an edit means -
 * a new revision, a re-publish, a correction - is a product question nobody has answered.
 */
@Service
public class CuratedPostImporter {

    /** What one run did, per post, in the order the plan listed them. */
    public record ImportReport(List<Entry> entries) {

        public record Entry(UUID postId, Outcome outcome, String detail) { }

        public enum Outcome { PUBLISHED, ALREADY_PRESENT }

        public long published() {
            return entries.stream().filter(entry -> entry.outcome() == Outcome.PUBLISHED).count();
        }
    }

    private final FeedStore feed;
    private final CatalogPlaceQuery catalog;
    private final Clock clock;

    public CuratedPostImporter(FeedStore feed, CatalogPlaceQuery catalog, Clock clock) {
        this.feed = Objects.requireNonNull(feed, "feed");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Imports the whole plan in one transaction.
     *
     * <p>One transaction for the file, not one per post: a partly imported feed is a state nobody
     * asked for, and an operator who has to work out which half landed is worse off than one who
     * fixes the file and runs it again.
     */
    @Transactional
    public ImportReport importPlan(CuratedPostPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Instant now = clock.instant();
        List<ImportReport.Entry> entries = new ArrayList<>(plan.posts().size());
        for (CuratedPost post : plan.posts()) {
            entries.add(importOne(post, now));
        }
        return new ImportReport(List.copyOf(entries));
    }

    private ImportReport.Entry importOne(CuratedPost post, Instant now) {
        if (feed.postExists(post.id())) {
            return new ImportReport.Entry(post.id(), ImportReport.Outcome.ALREADY_PRESENT,
                    "left as it is");
        }
        Map<UUID, UUID> canonical = canonicalPlaceIds(post);
        UUID assetId = feed.insertFirstPartyCover(UUID.randomUUID(), post.cover().url(),
                post.cover().alt(), post.cover().checksum(), now);
        feed.insertDraftPost(post.id(), post.title(), post.body(), post.cover().url(), assetId, now);
        int position = 0;
        for (CuratedPlace place : ordered(post)) {
            feed.linkPostPlace(post.id(), canonical.get(place.placeId()), position++,
                    place.primary() ? "PRIMARY" : "SECONDARY");
        }
        feed.publishPost(post.id(), post.publishedAt(), now);
        return new ImportReport.Entry(post.id(), ImportReport.Outcome.PUBLISHED,
                post.places().size() + " place(s)");
    }

    /**
     * The canonical id for every place the post names, and a refusal if any is missing.
     *
     * <p>The returned id is not always the one asked for: a deprecated id resolves to the canonical
     * row it was merged into, which is what keeps a post from linking a duplicate that later reads as
     * a different place. So the canonical id is what gets stored, and the plan file stays valid after
     * a merge it never knew about.
     *
     * <p>One lookup per place, not one statement for all of them. {@code summaries} orders by the
     * canonical id and says nothing about the order it was asked in, so pairing its results with the
     * request positionally maps a place to whichever row happened to sort first - which is how this
     * method first linked a post's PRIMARY place to its secondary one. There is no field in a summary
     * saying which requested id produced it, so the only way to know is to ask one at a time. The N
     * calls that {@code summaries} exists to avoid are the right shape here: this runs once, over a
     * handful of places, from an operator's terminal.
     *
     * <p>{@code summaries} and not {@code find}, which is the stricter one. The detail projection also
     * requires coordinates, because a detail screen puts the place on a map; a feed card renders a
     * summary and needs none. Gating curation on the detail query would refuse places the feed can
     * display perfectly well - a stricter check is not a safer one when it is checking the wrong
     * thing.
     */
    private Map<UUID, UUID> canonicalPlaceIds(CuratedPost post) {
        Map<UUID, UUID> canonical = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (CuratedPlace place : post.places()) {
            // The locale only chooses a display name, which this never stores; the lookup is being
            // used as an existence and canonicality check.
            UUID resolved = catalog.summaries(List.of(place.placeId()), "ko-KR", now).stream()
                    .findFirst()
                    .map(CatalogPlaceQuery.CatalogPlaceSummary::id)
                    // The id is echoed because it came from the operator's own file, not from a user.
                    .orElseThrow(() -> new CurationException("post " + post.id() + " names place "
                            + place.placeId() + ", which the catalog does not have as an active"
                            + " canonical row; a curated post cannot create a place"));
            canonical.put(place.placeId(), resolved);
        }
        return canonical;
    }

    /** PRIMARY first, so it lands at position 0 - which is what Post.primaryPlaceId() reads. */
    private static List<CuratedPlace> ordered(CuratedPost post) {
        List<CuratedPlace> ordered = new ArrayList<>(post.places());
        ordered.sort((left, right) -> Boolean.compare(right.primary(), left.primary()));
        return ordered;
    }
}
