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
}
