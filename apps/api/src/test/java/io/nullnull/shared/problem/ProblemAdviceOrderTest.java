package io.nullnull.shared.problem;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.nullnull.identity.api.IdentityProblemHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * BA-003: the {@code @Order} on the two {@code @RestControllerAdvice} classes is load-bearing, and
 * until now nothing failed when it was removed.
 *
 * <p>{@link GlobalExceptionHandler} owns a catch-all {@code Exception} handler and Spring stops at
 * the first advice with a match, so an unordered {@link IdentityProblemHandler} would race it and a
 * mapped module failure would come back as an unexplained 500 - intermittently, on bean-definition
 * order. Deleting either annotation left the whole integration suite green, because the current
 * order happens to be right by accident today.
 */
@DisplayName("BA-003 Problem advice ordering")
class ProblemAdviceOrderTest {

    @Test
    @DisplayName("both advices declare the precedence their javadoc calls load-bearing")
    void bothAdvicesDeclareAnExplicitOrder() {
        // Asserted on the ANNOTATION, not only on the comparator: an advice with no @Order falls back
        // to LOWEST_PRECEDENCE, which is the value GlobalExceptionHandler wants anyway, so removing
        // its annotation changes nothing a sort can see - and leaves the next advice added to the
        // package to win or lose the race at random.
        Order identity = IdentityProblemHandler.class.getAnnotation(Order.class);
        Order global = GlobalExceptionHandler.class.getAnnotation(Order.class);

        assertThat(identity).as("IdentityProblemHandler must rank first, explicitly").isNotNull();
        assertThat(identity.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(global).as("GlobalExceptionHandler must rank last, explicitly").isNotNull();
        assertThat(global.value()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("the module advice outranks the catch-all under Spring's own comparator")
    void theModuleAdviceOutranksTheCatchAll() {
        List<Object> advices = new ArrayList<>(
                List.of(new GlobalExceptionHandler(), new IdentityProblemHandler()));

        AnnotationAwareOrderComparator.sort(advices);

        assertThat(advices).element(0).isInstanceOf(IdentityProblemHandler.class);
        assertThat(advices).element(1).isInstanceOf(GlobalExceptionHandler.class);
    }

    @Test
    @DisplayName("every advice in the service declares its own precedence")
    void everyRestControllerAdviceDeclaresItsOrder() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.nullnull");

        classes().that().areAnnotatedWith(RestControllerAdvice.class)
                .should().beAnnotatedWith(Order.class)
                .because("an advice with no explicit order races the catch-all in GlobalExceptionHandler")
                .check(classes);
    }
}
