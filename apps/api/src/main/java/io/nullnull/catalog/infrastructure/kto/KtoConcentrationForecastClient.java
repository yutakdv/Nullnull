package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.crowd.application.KtoForecastFetcher;
import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded transport adapter for C4's separately approved KTO concentration endpoint. */
@Component
public class KtoConcentrationForecastClient implements KtoForecastFetcher {

    private final ProviderHttpClient provider;
    private final KtoKorServiceProperties properties;
    private final boolean testEndpointAllowed;

    public KtoConcentrationForecastClient(ProviderHttpClient provider, KtoKorServiceProperties properties,
            @Value("${nullnull.env}") String environment) {
        this.provider = provider;
        this.properties = properties;
        this.testEndpointAllowed = "test".equals(environment);
    }

    @Override
    public void requireConfigured() {
        properties.requireForecastConfigured(testEndpointAllowed);
    }

    @Override
    public CompletableFuture<ProviderResponse> fetch(KtoForecastRequest request) {
        return provider.get(KtoForecastSnapshotSet.SOURCE_CODE,
                properties.concentrationForecastUri(request.areaCode(), request.sigunguCode(), request.touristSiteName(),
                        testEndpointAllowed));
    }

    @Override
    public String releaseVersion() {
        return properties.releaseVersion();
    }
}
