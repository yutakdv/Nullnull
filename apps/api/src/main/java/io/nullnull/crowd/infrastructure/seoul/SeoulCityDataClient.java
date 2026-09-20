package io.nullnull.crowd.infrastructure.seoul;

import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Infrastructure adapter for one Seoul live area.
 *
 * <p>It reaches the provider through a proxy we run, so two things travel differently from every
 * other adapter here: the credential is NOT in the URI - the proxy holds the Seoul key and appends
 * it upstream - and this side authenticates with a shared token in a header. The header is the only
 * place that token may go; a path would put it in the part access logs record.
 */
@Component
public class SeoulCityDataClient {

    private final ProviderHttpClient provider;
    private final SeoulCityDataProperties properties;
    private final boolean testEndpointAllowed;

    public SeoulCityDataClient(ProviderHttpClient provider, SeoulCityDataProperties properties,
            @Value("${nullnull.env}") String environment) {
        this.provider = provider;
        this.properties = properties;
        this.testEndpointAllowed = "test".equals(environment);
    }

    public void requireConfigured() {
        properties.requireConfigured(testEndpointAllowed);
    }

    /** One area, by the name the provider's path takes (AREA_NM), never by our AREA_CD. */
    public CompletableFuture<ProviderResponse> fetch(String areaName) {
        return provider.get(SeoulLiveAreaObservation.SOURCE_CODE,
                properties.cityDataUri(areaName, testEndpointAllowed), properties.proxyHeaders());
    }
}
