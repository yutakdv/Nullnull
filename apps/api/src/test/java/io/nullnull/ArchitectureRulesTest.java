package io.nullnull;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.nullnull.operations.application.JobHandler;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * REC-ARCH-01 and BA-001-T2: module and layer boundaries from
 * docs/architecture/SYSTEM_ARCHITECTURE.md §4, §15, §16. The recommendation package, crowd.domain and
 * trip.domain (the lock validator every trip mutation shares) are pure: no Spring, JPA, servlet, JDBC,
 * HTTP client, clock or randomness.
 */
@DisplayName("REC-ARCH-01 architecture rules")
class ArchitectureRulesTest {

    static final String[] MODULES = {"identity", "catalog", "social", "trip", "recommendation",
            "crowd", "optimization", "live", "importer", "analytics", "operations"};

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.nullnull");
    }

    /**
     * The third defect class this session found, made repeatable. A service can be fully implemented,
     * fully tested and called by nobody in production - KtoSnapshotCatalogIngest was, and the C4
     * forecast smoke could not run because of it. Mutation testing does not find that (the code is
     * correct), and neither does coverage (the tests call it directly). The only question that finds
     * it is "who calls this on a production path".
     *
     * <p>Every class in recommendation.application either has an incoming production dependency or is
     * listed here with the slice that will give it one. Wiring a listed class means deleting its line,
     * which is the moment to check whether its neighbours were wired too - ProposalRevalidator is the
     * revalidation that keeps the AI from being the final judge (invariant 9), and a feed slice that
     * wired FeedFallback without it would be a silent hole.
     */
    private static final java.util.Map<String, String> AWAITING_THEIR_SLICE = java.util.Map.of(
            // Both labels said "BA-050 feed slice" and both were wrong, in different ways. BA-050 has
            // no feed in it at all - it is the optimization run - and the P0 feed is fixed order by
            // its own card's title, so the slice that first calls rankFeed is BA-080, which is P1.
            // RunFingerprint and ProposalRevalidator were listed here for BA-051, and BA-051 now calls
            // both from OptimizeItemHandler - so their lines are gone rather than kept as paperwork.
            // Deleting them is the point of the list: a name stays only while nothing in production
            // calls the class, and this map shrinking is what "the slice arrived" looks like.
            "FeedFallback", "BA-080 ranked feed slice");

    @Test
    @DisplayName("REC-ARCH-01 an uncalled recommendation service names the slice that will call it")
    void everyRecommendationServiceIsCalledOrRegisteredAsAwaitingItsSlice() {
        java.util.Set<String> uncalled = new java.util.TreeSet<>();
        for (com.tngtech.archunit.core.domain.JavaClass candidate : classes) {
            if (!candidate.getPackageName().equals("io.nullnull.recommendation.application")
                    || candidate.isInterface() || candidate.isEnum() || candidate.isRecord()
                    || candidate.getSimpleName().endsWith("Exception")
                    || candidate.getSimpleName().contains("$")) {
                continue;
            }
            boolean called = candidate.getDirectDependenciesToSelf().stream()
                    .anyMatch(dependency -> !dependency.getOriginClass().equals(candidate));
            if (!called) {
                uncalled.add(candidate.getSimpleName());
            }
        }

        org.assertj.core.api.Assertions.assertThat(uncalled)
                .as("a recommendation service with no production caller must say which slice adds one")
                .containsExactlyInAnyOrderElementsOf(new java.util.TreeSet<>(AWAITING_THEIR_SLICE.keySet()));
        org.assertj.core.api.Assertions.assertThat(AWAITING_THEIR_SLICE.values())
                .allSatisfy(slice -> org.assertj.core.api.Assertions.assertThat(slice).isNotBlank());
    }

    @Test
    void recommendationPackageHasNoFrameworkOrIoDependencies() {
        // The HTTP adapter to apps/ai belongs to recommendation.infrastructure; domain/application stay pure.
        noClasses().that().resideInAnyPackage("io.nullnull.recommendation.domain..", "io.nullnull.recommendation.application..",
                        "io.nullnull.crowd.domain..", "io.nullnull.trip.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..",
                        "java.sql..", "javax.sql..", "java.net.http..", "org.hibernate..",
                        "io.nullnull..infrastructure..", "io.nullnull..api..")
                .because("recommendation only computes over injected immutable inputs (§14, §15)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void recommendationPackageNeverReadsTheClockOrRandomness() {
        noClasses().that().resideInAnyPackage("io.nullnull.recommendation.domain..", "io.nullnull.recommendation.application..",
                        "io.nullnull.crowd.domain..", "io.nullnull.trip.domain..")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(Clock.class, "systemUTC")
                .orShould().callMethod(Clock.class, "systemDefaultZone")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(UUID.class, "randomUUID")
                .orShould().callMethod(Math.class, "random")
                .orShould().dependOnClassesThat().belongToAnyOf(Random.class, SecureRandom.class,
                        ThreadLocalRandom.class)
                .because("P0 results must be reproducible from snapshot + evaluatedAt + policy hash (§6)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void tripDomainNeverDependsOnRecommendation() {
        // The lock validator is trip-owned and shared with manual edits, replacements and APPLY;
        // recommendation depends on it, never the other way round (Ruling 4, invariant 7).
        noClasses().that().resideInAPackage("io.nullnull.trip.domain..")
                .should().dependOnClassesThat().resideInAPackage("io.nullnull.recommendation..")
                .because("trip locks are not a recommendation concept")
                .allowEmptyShould(true)
                .check(classes);
    }

    /**
     * BA-033-T3's first clause, which nothing else covers.
     *
     * <p>The card asks that "analytics 장애가 제품 command를 실패시키지 않는다". Three tests carry that
     * ID and all three are about its second half - owner deletion and the retention TTL - so the
     * first half was a sentence with no test under it. It is true today, and it is true for a
     * structural reason rather than by care: no product command can reach analytics at all, so an
     * analytics failure has no path by which to fail one.
     *
     * <p>Pinning the structure is the honest way to prove it. The alternative - making the ingest
     * path fail and watching a command succeed - would prove it for one command on one day, while
     * this fails the moment any code outside the module names it, which is exactly when someone has
     * to decide whether a product command may now depend on analytics and what happens when it
     * breaks. A future slice that wants server-side emission should expect to see this test and
     * answer that question rather than delete it.
     */
    @Test
    @DisplayName("BA-033-T3 no product command can reach analytics, so an analytics failure cannot fail one")
    void analyticsIsNeverOnAProductCommandsPath() {
        // Not vacuous in either direction: the module has classes to depend on, and there is code
        // outside it that could have.
        org.assertj.core.api.Assertions.assertThat(classes.stream()
                        .filter(candidate -> candidate.getPackageName().startsWith("io.nullnull.analytics"))
                        .count())
                .as("the rule is about a module that exists")
                .isGreaterThan(3);

        noClasses().that().resideOutsideOfPackage("io.nullnull.analytics..")
                .should().dependOnClassesThat().resideInAPackage("io.nullnull.analytics..")
                .because("a command that cannot call analytics cannot be failed by it (BA-033-T3)")
                .check(classes);
    }

    /**
     * BA-060-T4: the pasted itinerary has no route out of the importer.
     *
     * <p>What this proves is narrower than "the raw text never leaves", and the narrower claim is the
     * one worth making. ArchUnit reads types, not values: it cannot see a String travel, so a call
     * like {@code catalog.search(wholeLine)} would pass this and still hand a whole pasted line to
     * another module. What it does prove is that the importer holds no reference to anything that can
     * speak to the network - the apps/ai gateway, a RestClient, a JDK HttpClient - so sending the
     * paste somewhere requires first adding a dependency here, and that is the moment someone has to
     * justify it. Invariant 10 and the card's safety line are what they would be justifying against.
     *
     * <p>The value half is the canary's job (BA-060-T1), not this test's. Naming them apart matters:
     * a rule called "raw text cannot escape" would be believed to cover more than it does, and the
     * gap it left would be invisible precisely because the name sounded complete.
     */
    @Test
    @DisplayName("BA-060-T4 the importer holds no reference to anything that can leave the process")
    void theImporterCannotReachTheNetworkOrTheRecommendationGateway() {
        // Not vacuous in either direction. The module has to exist, or the rule is about nothing;
        // and the packages it may not reach have to have classes in them, or there was never
        // anything to forbid. The second half is the one that rots quietly - a gateway package that
        // was emptied would leave this test green while proving strictly less than it claims.
        org.assertj.core.api.Assertions.assertThat(classes.stream()
                        .filter(candidate -> candidate.getPackageName().startsWith("io.nullnull.importer"))
                        .count())
                .as("the rule is about a module that exists")
                .isPositive();
        org.assertj.core.api.Assertions.assertThat(classes.stream()
                        .filter(candidate -> candidate.getPackageName()
                                .startsWith("io.nullnull.recommendation"))
                        .count())
                .as("the gateway it may not reach has classes to reach")
                .isPositive();

        noClasses().that().resideInAPackage("io.nullnull.importer..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.nullnull.recommendation..", "java.net.http..",
                        "org.springframework.web.client..")
                .because("the pasted itinerary is never sent anywhere, so the importer keeps no way"
                        + " to send it (BA-060-T4, invariant 10)")
                .check(classes);
    }

    @Test
    void domainLayerDependsOnNothingAbove() {
        noClasses().that().resideInAPackage("io.nullnull..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.nullnull..api..", "io.nullnull..application..", "io.nullnull..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void applicationLayerDoesNotDependOnApiOrInfrastructure() {
        noClasses().that().resideInAPackage("io.nullnull..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.nullnull..api..", "io.nullnull..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void apiLayerDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("io.nullnull..api..")
                .should().dependOnClassesThat().resideInAPackage("io.nullnull..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void apiLayerNeverExposesJpaEntities() {
        noClasses().that().resideInAPackage("io.nullnull..api..")
                .should().dependOnClassesThat().areAnnotatedWith(jakarta.persistence.Entity.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("BA-001-T2 modules never reach into another module's repository or infrastructure")
    void modulesNeverReachIntoAnotherModulesInfrastructure() {
        for (String module : MODULES) {
            noClasses().that().resideOutsideOfPackage("io.nullnull." + module + "..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("io.nullnull." + module + ".infrastructure..")
                    .because("modules use public application services or domain events, never another "
                            + "module's repository/table (§4)")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    /**
     * The half of "a handler writes only through {@code JobContext.transactional}" that
     * {@code JobUnitOfWorkGuard} cannot enforce. The guard is a transaction listener, so it sees
     * begins; a statement run in autocommit through one of these types begins nothing and commits
     * unbound, with no lease assertion anywhere near it. Forbidding the types narrows that hole to what a
     * dependency check can see: a handler that names one of them. A collaborator one hop away is not
     * covered - see {@code JobUnitOfWorkGuard} and the BA-005 card. A handler writes through an
     * application service of the owning module, called inside the unit of work.
     */
    @Test
    void jobHandlersNeverTouchTheDatabaseDirectly() {
        noClasses().that().implement(JobHandler.class)
                .should().dependOnClassesThat().belongToAnyOf(JdbcClient.class, JdbcTemplate.class,
                        EntityManager.class, DataSource.class, Connection.class)
                .because("a write outside JobContext.transactional is not bound to the lease, and a "
                        + "statement in autocommit never reaches JobUnitOfWorkGuard (REC-JOB-01)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void sharedPackageStaysTechnicalOnly() {
        String[] modulePackages = new String[MODULES.length];
        for (int i = 0; i < MODULES.length; i++) {
            modulePackages[i] = "io.nullnull." + MODULES[i] + "..";
        }
        noClasses().that().resideInAPackage("io.nullnull.shared..")
                .should().dependOnClassesThat().resideInAnyPackage(modulePackages)
                .because("shared holds technical values only, never recommendation, trip or source policy (§16)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("BA-010-T7 only the session interceptor can report a missing session cookie")
    void missingSessionCookieHasOneProducer() {
        // getMethod throws if the factory is renamed away, so this cannot pass by finding nothing to check. Accesses,
        // not just calls, so a method reference (ApiException::missingSessionCookie) counts as a producer too.
        var accesses = classes.get(io.nullnull.shared.problem.ApiException.class)
                .getMethod("missingSessionCookie", String.class).getAccessesToSelf();
        // A second producer - SessionService.unauthorized(), say - would widen the bit from "no cookie was sent" to
        // "this cookie is no longer good". The contract enum does not change, so oasdiff would not notice.
        String owner = io.nullnull.identity.api.SessionHttpConfiguration.class.getName();
        org.assertj.core.api.Assertions.assertThat(accesses).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(accesses.iterator().next().getOriginOwner().getName())
                .matches(java.util.regex.Pattern.quote(owner) + "(\\$.*)?");
        // The other way to put the field on a body is to build the Problem directly; its setter is package-private,
        // and its constructor may only be called from inside shared.problem, where the factory's path runs.
        org.assertj.core.api.Assertions.assertThat(classes.get(io.nullnull.shared.problem.Problem.class).getConstructors())
                .flatMap(constructor -> constructor.getAccessesToSelf())
                .allSatisfy(access -> org.assertj.core.api.Assertions.assertThat(access.getOriginOwner().getPackageName())
                        .isEqualTo("io.nullnull.shared.problem"));
    }

    @Test
    @DisplayName("BA-010-T5 a refused session cookie gets its 401 from SessionService.unauthorized() and nowhere else")
    void aRefusedCookieHasOneAnswer() {
        // #249: two 401s once carried sentences of their own ("owner no longer active", "deletion replay required"),
        // each telling the caller the cookie had been valid. Any new producer of this code would be a third. Origins
        // are methods, so a second method in an allowed class is caught too.
        var origins = classes.get(io.nullnull.shared.problem.ProblemCode.class).getField("UNAUTHORIZED")
                .getAccessesToSelf().stream().map(access -> access.getOrigin().getFullName()).distinct().toList();
        org.assertj.core.api.Assertions.assertThat(origins)
                .contains("io.nullnull.identity.application.SessionService.unauthorized()")
                .allSatisfy(origin -> org.assertj.core.api.Assertions.assertThat(origin).matches(
                        "io\\.nullnull\\.identity\\.application\\.SessionService\\.unauthorized\\(\\)"
                        // No cookie was sent at all - BA-010-T4, and T7 pins its one caller.
                        + "|io\\.nullnull\\.shared\\.problem\\.ApiException\\.missingSessionCookie\\(java\\.lang\\.String\\)"
                        // A 401 the container raised. Nothing does: no container authentication is configured and
                        // main code has no sendError call (grep for sendError and SC_UNAUTHORIZED, #249).
                        + "|io\\.nullnull\\.shared\\.problem\\.ProblemErrorController\\.error\\(.*\\)"
                        // The enum's own declaration.
                        + "|io\\.nullnull\\.shared\\.problem\\.ProblemCode\\..*"));
    }
}
