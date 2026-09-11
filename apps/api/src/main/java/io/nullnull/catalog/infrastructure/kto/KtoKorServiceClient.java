package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.catalog.application.KtoPlaceDetailFetcher;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Infrastructure adapter for KTO KorService2; no other KTO operation is callable from C2. */
@Component
public class KtoKorServiceClient implements KtoPlaceDetailFetcher {

    private final ProviderHttpClient provider;
    private final KtoKorServiceProperties properties;
    private final boolean testEndpointAllowed;

    public KtoKorServiceClient(ProviderHttpClient provider, KtoKorServiceProperties properties,
            @Value("${nullnull.env}") String environment) {
        this.provider = provider;
        this.properties = properties;
        this.testEndpointAllowed = "test".equals(environment);
    }

    @Override
    public void requireConfigured() {
        properties.requireConfigured(testEndpointAllowed);
    }

    @Override
    public CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request) {
        return provider.get(KtoPlaceSnapshot.SOURCE_CODE,
                properties.detailCommonUri(request, testEndpointAllowed));
    }

    @Override
    public String releaseVersion() {
        return properties.releaseVersion();
    }
}
