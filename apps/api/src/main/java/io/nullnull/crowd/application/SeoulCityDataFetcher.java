package io.nullnull.crowd.application;

import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Infrastructure port for the one Seoul citydata operation the proxy accepts.
 *
 * <p>The collection flow lives in an application service and must not name the adapter: this
 * provider is reached through a proxy we run, and the adapter is where the proxy's base URL and
 * shared token live. Naming it from application would put the one class that holds a credential on
 * the import list of every caller, which is also what {@code ArchitectureRulesTest} forbids.
 */
public interface SeoulCityDataFetcher {

    /** Fails before a collector run is opened when the proxy endpoint or token is not configured. */
    void requireConfigured();

    /** One area, by the name the provider's path takes (AREA_NM), never by our AREA_CD. */
    CompletableFuture<ProviderResponse> fetch(String areaName);
}
