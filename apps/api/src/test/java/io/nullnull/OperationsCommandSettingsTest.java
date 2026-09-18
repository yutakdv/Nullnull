package io.nullnull;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Where an operations command puts the settings it just loaded (#226).
 *
 * <p>{@code SpringApplicationBuilder.properties(...)} writes into {@code defaultProperties}, the
 * LOWEST precedence Spring Boot has - below {@code application.yaml}. Every operator setting this
 * repository loads has a yaml line of the form {@code ${KTO_SERVICE_KEY:}}, which resolves to an
 * empty string when the process environment has no such variable, and an empty string from a higher
 * source beats a real value from a lower one. So the file was read, printed as read, and then
 * overridden by a blank. {@code KtoSmokeEnvironment.applying} is the same settings put where they
 * win ({@code addFirst}).
 *
 * <p><strong>This is a class of defect, not an instance.</strong> #226 was fixed in two of the
 * commands and the fix did not reach the third; the miss was invisible because the command still
 * printed {@code <- .env.local} for every setting, so its output was identical to a working one. The
 * next operations command that copies the pattern would copy the broken half again, and counting the
 * copies by eye is what let this one through.
 *
 * <p><strong>Why the rule bans the wrong call rather than requiring the right one.</strong> Two
 * shapes were measured and both would report classes that are correct:
 *
 * <ul>
 * <li><em>"a command that builds a context must call applying"</em> - {@code CatalogRelationDeriveMain},
 *     {@code CuratedHoursImportMain} and {@code CuratedPostImportMain} build one and load no operator
 *     settings at all. They have nothing to apply.
 * <li><em>"a command that calls load must call applying"</em> - {@code KtoIntroProbeMain} loads
 *     settings and never starts Spring (it reads the provider's field shapes over plain HTTP), so it
 *     has no environment to apply them to.
 * </ul>
 *
 * <p>Banning the call has no such exception today: nothing in this repository has a reason to write
 * into {@code defaultProperties}, and the three overloads are banned together because which one a
 * future copy reaches for is not predictable.
 *
 * <p>No acceptance ID leads this test. {@code BA-021} is the KTO gateway card and its files are
 * frozen, so it cannot take a new clause; attaching one of its existing IDs would claim a proof that
 * clause never asked for (#195). It runs because the suite runs it.
 */
@DisplayName("operations command settings")
class OperationsCommandSettingsTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.nullnull");
    }

    @Test
    @DisplayName("#226 loaded operator settings never go into defaultProperties, where a blank yaml default beats them")
    void loadedSettingsNeverGoIntoDefaultProperties() {
        // Not vacuous: the builder this rule is about is one this repository actually uses. Without
        // this, deleting every operations command would leave the rule green and say nothing.
        assertThat(classes.stream()
                .flatMap(candidate -> candidate.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(SpringApplicationBuilder.class))
                .count())
                .as("the rule is about a builder this repository calls")
                .isPositive();

        noClasses().should().callMethod(SpringApplicationBuilder.class, "properties", String[].class)
                .orShould().callMethod(SpringApplicationBuilder.class, "properties", Properties.class)
                .orShould().callMethod(SpringApplicationBuilder.class, "properties", Map.class)
                .because("defaultProperties is the lowest precedence there is, so a yaml default of "
                        + "${VAR:} blanks the value that was just loaded; KtoSmokeEnvironment.applying "
                        + "adds the same map first instead (#226)")
                .allowEmptyShould(true)
                .check(classes);
    }
}
