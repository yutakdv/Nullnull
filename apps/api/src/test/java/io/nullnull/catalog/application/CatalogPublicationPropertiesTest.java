package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CatalogPublicationPropertiesTest {

    private static final String SECRET = "test-catalog-cursor-secret-that-is-long-enough";

    @Test
    void aDisabledProductionProjectionFailsClosedWithoutNeedingACursorSecret() {
        MockEnvironment production = production();
        CatalogPublicationProperties properties = new CatalogPublicationProperties(false, "", production);

        assertThatThrownBy(properties::requirePublicProjection)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ProblemCode.SOURCE_UNAVAILABLE);
    }

    @Test
    void anEnabledProductionProjectionRequiresTheSeparateCursorSecret() {
        MockEnvironment production = production();

        assertThatThrownBy(() -> new CatalogPublicationProperties(true, "", production))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NULLNULL_CURSOR_SECRET");
        assertThatCode(() -> new CatalogPublicationProperties(true, SECRET, production))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }
}
