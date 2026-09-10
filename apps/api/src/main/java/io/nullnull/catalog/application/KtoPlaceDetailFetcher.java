package io.nullnull.catalog.application;

import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;

/** Infrastructure port for the single approved KorService2 detail operation. */
public interface KtoPlaceDetailFetcher {

    /** Fails before a collector run is opened when the runtime is not safe to make a call. */
    void requireConfigured();

    CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request);

    /** Release identifier retained in the safe audit ledger, never a credential. */
    String releaseVersion();
}
