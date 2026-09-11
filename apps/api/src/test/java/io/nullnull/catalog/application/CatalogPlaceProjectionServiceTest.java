package io.nullnull.catalog.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CatalogPlaceProjectionServiceTest {

    @Test
    void aDisabledPublicationGateStopsTheSearchBeforeAnyCatalogRead() {
        CatalogPlaceQuery catalog = mock(CatalogPlaceQuery.class);
        CatalogPlaceProjectionService service = new CatalogPlaceProjectionService(catalog,
                new CatalogPublicationProperties(false, "", production()), mock(OwnerPreferencesService.class),
                Clock.fixed(Instant.parse("2032-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.search(new OwnerContext(UUID.randomUUID(), UUID.randomUUID(), false),
                CatalogPlaceSearchRequest.of("장소", "ko-KR", null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ProblemCode.SOURCE_UNAVAILABLE);
        verifyNoInteractions(catalog);
    }

    private static MockEnvironment production() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        return environment;
    }
}
