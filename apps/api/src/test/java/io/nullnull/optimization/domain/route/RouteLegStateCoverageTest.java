package io.nullnull.optimization.domain.route;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Leg;
import io.nullnull.optimization.infrastructure.kakao.KakaoRouteClient;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-083-T23: every {@link Leg} state accounts for itself.
 *
 * <p>Same device as {@code RelationStateCoverageTest} and {@code CrowdQualityFlagCoverageIT}, on a
 * fourth vocabulary. It exists because {@code Unavailable} is the shape those tests were written for:
 * a state that is fully implemented - {@link DirectedRouteMatrix} stores it, {@link RouteFeasibility}
 * spends {@code ROUTE_UNAVAILABLE} on it, and {@code BA-083-T5} proves that reason is distinct - and
 * that nothing can currently produce.
 *
 * <p><b>Why it has no producer.</b> The adapter can only call a pair unroutable if it knows which
 * {@code result_code} values mean "there is no road". The single live call this design rests on
 * succeeded ({@code result_code: 0}), so that vocabulary is unconfirmed, and naming an unrecognised
 * failure "the provider said there is no route" would be inventing the provider's answer.
 * {@code Absent} - "nobody asked, or the answer never arrived" - is the weaker statement and the true
 * one. The entry goes away the day a second call establishes the vocabulary.
 *
 * <p><b>The state is not deleted meanwhile.</b> Removing it would make the register tidy and would
 * throw away the distinction {@code BA-083-T5} pins, along with the reason {@code RouteFeasibility}
 * already reports. What must not happen is that it quietly looks implemented.
 */
@DisplayName("BA-083-T23 route leg state coverage")
class RouteLegStateCoverageTest {

    /** States something produces today, and where that is shown. */
    private static final Map<String, String> PRODUCED_BY = new LinkedHashMap<>(Map.of(
            "Available", "KakaoRouteClientTest: a result_code 0 response with summary.duration becomes"
                    + " a travel time",
            "Absent", "KakaoRouteClientTest: a refused, failed, unparseable or duration-less answer"
                    + " leaves the pair unanswered"));

    /** States nothing produces, with why. Cross-examined below, so a stale entry fails. */
    private static final Map<String, String> NO_PRODUCER = new LinkedHashMap<>(Map.of(
            "Unavailable", "naming it requires knowing which Kakao result_code values mean 'no route';"
                    + " one live call established only that 0 means success, so an unrecognised"
                    + " failure stays Absent rather than becoming an invented provider answer"));

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.nullnull");
    }

    @Test
    @DisplayName("BA-083-T23 every leg state is produced or is recorded as having no producer")
    void everyStateAccountsForItself() {
        Set<String> declared = Arrays.stream(Leg.class.getPermittedSubclasses())
                .map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));

        // Not vacuous: the sealed interface has to have told us something.
        assertThat(declared).as("Leg's permitted states were found").isNotEmpty();
        // A fourth state added tomorrow is in neither register and fails here, which is the whole
        // point of reading them off the type rather than listing them by hand.
        assertThat(declared).allSatisfy(state -> assertThat(
                PRODUCED_BY.containsKey(state) ^ NO_PRODUCER.containsKey(state))
                .as("%s must be recorded as produced or as having no producer, and not both", state)
                .isTrue());
        assertThat(PRODUCED_BY.keySet()).allSatisfy(state -> assertThat(declared).contains(state));
        assertThat(NO_PRODUCER.keySet()).allSatisfy(state -> assertThat(declared).contains(state));
    }

    @Test
    @DisplayName("BA-083-T23 the route adapter is the only thing that can build a matrix")
    void theOnlyProducerIsTheAdapter() {
        // What bounds the claim. Unavailable can only enter a matrix through DirectedRouteMatrix.of's
        // second argument, so "nothing produces it" is checkable exactly as far as the set of callers
        // of that method is known. Today there is one, and its call passes Set.of() - which the
        // register above describes and KakaoRouteClientTest exercises. A second production caller
        // appearing is the event that would make this register a guess again, so it fails here first.
        assertThat(classes.contain(KakaoRouteClient.class))
                .as("the adapter is imported, or forbidding everyone else forbids nothing").isTrue();

        noClasses().that().resideInAPackage("io.nullnull..")
                .and().doNotBelongToAnyOf(KakaoRouteClient.class)
                .should().callMethod(DirectedRouteMatrix.class, "of", Map.class, Set.class)
                .because("a second builder of route matrices would have its own answer about"
                        + " Unavailable, and BA-083-T23's register speaks for one (A-055)")
                .check(classes);
    }
}
