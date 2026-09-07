package io.nullnull;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
