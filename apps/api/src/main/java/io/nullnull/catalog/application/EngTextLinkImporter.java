package io.nullnull.catalog.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the owner's reviewed decisions "this EngService record is that canonical place" (BA-086). The
 * owner reviews candidates against their own rule (#60, 2026-09-21) and records the review time and the
 * page they looked at in the plan; this import stores the decision and nothing else. It makes no
 * provider call - the English text arrives only through {@link KtoEngTextRefresh}, which re-checks the
 * rule against the record it actually receives.
 *
 * <p>The evidence URL is validated and deliberately not stored or printed, as with the live-area plan.
 */
@Service
public class EngTextLinkImporter {

    private final EngTextStore store;
    private final Clock clock;

    public EngTextLinkImporter(EngTextStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Plan(List<Link> links) {
        public Plan {
            links = List.copyOf(Objects.requireNonNull(links, "links"));
            if (links.isEmpty() || links.stream().map(Link::placeId).distinct().count() != links.size()) {
                throw new IllegalArgumentException("the plan needs distinct places");
            }
        }
    }

    public record Link(UUID placeId, String contentId, String contentTypeId, Instant reviewedAt, String evidenceUrl) {
        public Link {
            Objects.requireNonNull(placeId, "placeId");
            Objects.requireNonNull(reviewedAt, "reviewedAt");
            KtoPlaceRequest request = new KtoPlaceRequest(contentId, contentTypeId);
            contentId = request.contentId();
            contentTypeId = request.contentTypeId();
            URI evidence;
            try {
                evidence = URI.create(evidenceUrl);
            } catch (RuntimeException invalid) {
                // No cause: URI.create quotes its input, and the evidence URL is not printed anywhere.
                throw new IllegalArgumentException("an HTTPS evidence URL is required");
            }
            if (!"https".equals(evidence.getScheme()) || evidence.getHost() == null || evidence.getUserInfo() != null) {
                throw new IllegalArgumentException("an HTTPS evidence URL is required");
            }
        }
    }

    /** One transaction: a plan with one refused entry leaves nothing behind. */
    @Transactional
    public List<UUID> importPlan(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        Instant now = clock.instant();
        for (Link link : plan.links()) {
            if (link.reviewedAt().isAfter(now)) {
                throw new IllegalArgumentException("a future review cannot be imported");
            }
            EngTextStore.PlaceSide place = store.lockActivePlace(link.placeId())
                    .orElseThrow(() -> new IllegalArgumentException("the plan names an unknown or inactive place"));
            if (!place.hasKoreanRecord()) {
                throw new IllegalArgumentException(
                        "the place has no Korean KTO record for the link rule to compare with");
            }
            Optional<UUID> holder = store.linkedPlace(link.contentId(), link.contentTypeId());
            if (holder.isPresent() && !holder.get().equals(link.placeId())) {
                throw new IllegalArgumentException("that English record is already linked to another place");
            }
            Optional<EngTextStore.Link> existing = store.lockLink(link.placeId());
            if (existing.isPresent()) {
                EngTextStore.Link previous = existing.get();
                boolean sameRecord = previous.externalId().equals(link.contentId())
                        && previous.contentTypeId().equals(link.contentTypeId());
                if (link.reviewedAt().isBefore(previous.reviewedAt())) {
                    throw new IllegalArgumentException("a newer review of this place already exists");
                }
                if (link.reviewedAt().equals(previous.reviewedAt())) {
                    if (!sameRecord) {
                        throw new IllegalArgumentException("a different decision with the same review time exists");
                    }
                    continue;
                }
                if (!sameRecord) {
                    // The text came from the record the owner has just replaced; it must not outlive the
                    // decision it rested on, even until the next refresh (BA-086-T23).
                    store.removeText(link.placeId());
                }
            }
            store.saveLink(new EngTextStore.Link(link.placeId(), link.contentId(), link.contentTypeId(),
                    link.reviewedAt()), now);
        }
        return plan.links().stream().map(Link::placeId).toList();
    }
}
