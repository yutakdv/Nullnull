package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.catalog.application.KtoEngDetailFetcher;
import io.nullnull.catalog.application.KtoEngRecord;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Infrastructure adapter for KTO EngService2; detailCommon2 is the only operation it can call. */
@Component
public class KtoEngServiceClient implements KtoEngDetailFetcher {

    private final ProviderHttpClient provider;
    private final KtoKorServiceProperties properties;
    private final boolean testEndpointAllowed;

    public KtoEngServiceClient(ProviderHttpClient provider, KtoKorServiceProperties properties,
            @Value("${nullnull.env}") String environment) {
        this.provider = provider;
        this.properties = properties;
        this.testEndpointAllowed = "test".equals(environment);
    }

    @Override
    public void requireConfigured() {
        properties.requireEngConfigured(testEndpointAllowed);
    }

    @Override
    public CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request) {
        return provider.get(KtoEngRecord.SOURCE_CODE, properties.engDetailCommonUri(request, testEndpointAllowed));
    }

    @Override
    public String releaseVersion() {
        return properties.releaseVersion();
    }
}
