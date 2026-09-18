package io.nullnull.recommendation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderRequest;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.related.RelatedRankRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-050-T8: what a request to {@code apps/ai} may contain, enforced on the type rather than on one
 * example of it.
 *
 * <p>The rule is named in CLAUDE.md and in ADR-0006 - "{@code apps/ai} 요청에 owner/session ID·원문·
 * 좌표를 넣지 않는다" - and until now nothing enforced it. The one assertion that looked like it did,
 * {@code jsonPath("$.ownerId").doesNotExist()} in {@code HttpRecommendationGatewayTest}, could not
 * fail: {@code ExplanationRenderRequest} has no {@code ownerId} component, so the field it asserted
 * was absent was one no serialiser could ever have written. A guard that cannot fire is worse than no
 * guard, because it makes the next reader believe the rule is covered.
 *
 * <p>So this walks the request types instead of one instance of them. Two things are checked and they
 * fail for different reasons:
 *
 * <ul>
 * <li>every component name in the whole request tree is one the rule permits, so ADDING a field
 *     called {@code ownerId} or {@code latitude} turns this red at the type - which is the direction
 *     a violation actually arrives from;</li>
 * <li>the names are checked recursively into nested records, because a coordinate does not have to
 *     be at the top level to reach the wire.</li>
 * </ul>
 */
@DisplayName("BA-050-T8 the recommendation request shape")
class RecommendationRequestShapeTest {

    /** Every request type the Spring gateway can send. */
    private static final List<Class<?>> REQUESTS = List.of(ItemProposeRequest.class,
            FeedRankRequest.class, RelatedRankRequest.class, SlotEvaluateRequest.class,
            ExplanationRenderRequest.class, DraftComposeRequest.class);

    /**
     * Fragments that must not appear in any component name, and what each one is about.
     *
     * <p>Substrings rather than exact names on purpose: {@code requestedByOwnerId} is the same
     * violation as {@code ownerId}, and a rule that only knew the exact spelling would be satisfied
     * by a rename.
     */
    private static final List<String> FORBIDDEN = List.of(
            // Invariant 11: the service never learns whose trip this is.
            "owner", "session", "account", "cookie", "token",
            // Invariant 10: no pasted text is stored, logged or sent anywhere.
            "rawtext", "pastedtext", "itinerarytext",
            // Invariant 10: precise location is not collected server-side in P0, so it cannot be sent.
            "latitude", "longitude", "coordinate", "geopoint");

    /**
     * Names that contain a forbidden fragment but are not the thing it is about.
     *
     * <p>Empty today, and kept as an explicit list rather than a cleverer rule so that the day
     * something legitimate collides, the exception is written down with its reason instead of the
     * check being loosened.
     */
    private static final Set<String> ALLOWED_DESPITE_MATCH = Set.of();

    @Test
    @DisplayName("BA-050-T8 no request field names an owner, a session, raw text or a coordinate")
    void theRequestTreeCarriesNothingItMayNot() {
        List<String> violations = new ArrayList<>();
        for (Class<?> request : REQUESTS) {
            collect(request, request.getSimpleName(), new LinkedHashSet<>(), violations);
        }
        assertThat(violations)
                .as("a request to apps/ai carries facts about a trip, never about who owns it")
                .isEmpty();
    }

    @Test
    @DisplayName("BA-050-T8 the walk actually reaches the nested records, and would fail on one")
    void theWalkIsNotVacuous() {
        // The other half of the previous test: "no violations" is also what an empty walk returns.
        // This pins that the walk sees the tree it claims to see - ItemProposeRequest's target is a
        // record, so its components must appear - and that a forbidden name anywhere in it is caught.
        Set<String> visited = new LinkedHashSet<>();
        collect(ItemProposeRequest.class, "ItemProposeRequest", visited, new ArrayList<>());
        assertThat(visited)
                .contains("ItemProposeRequest.target.placeId", "ItemProposeRequest.target.startTime")
                .hasSizeGreaterThan(20);

        List<String> violations = new ArrayList<>();
        collect(Offender.class, "Offender", new LinkedHashSet<>(), violations);
        assertThat(violations).singleElement().asString().contains("ownerId");
    }

    @Test
    @DisplayName("BA-055-T8 the draft compose request tree carries no owner, session, raw text or coordinate name")
    void theDraftRequestTreeCarriesNothingItMayNot() {
        // Its own case so the draft card can point at a testcase that walks ITS request, not at the
        // loop above that happens to include it. The visited set is asserted too: an empty walk also
        // reports no violation, and the pool's places are nested two records deep.
        Set<String> visited = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        collect(DraftComposeRequest.class, "DraftComposeRequest", visited, violations);
        assertThat(visited).contains("DraftComposeRequest.pool", "DraftComposeRequest.pool[].placeId",
                "DraftComposeRequest.pool[].openingHours");
        assertThat(violations).isEmpty();
    }

    /** A record shaped like the mistake this test exists to catch. */
    private record Offender(java.util.UUID tripId, Nested nested) {
        private record Nested(java.util.UUID ownerId) { }
    }

    private static void collect(Class<?> type, String path, Set<String> visited, List<String> violations) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String name = component.getName();
            String where = path + "." + name;
            if (!visited.add(where)) {
                continue;
            }
            if (!ALLOWED_DESPITE_MATCH.contains(name)) {
                String lower = name.toLowerCase(Locale.ROOT);
                FORBIDDEN.stream().filter(lower::contains)
                        .forEach(fragment -> violations.add(where + " names '" + fragment + "'"));
            }
            collect(component.getType(), where, visited, violations);
            for (Class<?> argument : typeArguments(component)) {
                collect(argument, where + "[]", visited, violations);
            }
        }
    }

    /** Element and value types of a generic component, so a List or Map is walked into. */
    private static Set<Class<?>> typeArguments(RecordComponent component) {
        Set<Class<?>> arguments = new LinkedHashSet<>();
        if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                if (argument instanceof Class<?> raw) {
                    arguments.add(raw);
                }
            }
        }
        return arguments;
    }

    @Test
    @DisplayName("BA-050-T8 every request type is walked, so a new one cannot be added unchecked")
    void everyRequestTypeIsListed() {
        // The list above is hand-written, so the failure mode is a new request type nobody adds to
        // it. RecommendationGateway is the one place every request type is named, and its parameter
        // types are therefore the set this must cover.
        Set<String> declared = new TreeSet<>();
        for (java.lang.reflect.Method method
                : io.nullnull.recommendation.application.RecommendationGateway.class.getMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.isRecord() && parameter.getPackageName().startsWith("io.nullnull.recommendation.domain")) {
                    declared.add(parameter.getSimpleName());
                }
            }
        }
        assertThat(REQUESTS).extracting(Class::getSimpleName).containsAll(declared);
    }
}
