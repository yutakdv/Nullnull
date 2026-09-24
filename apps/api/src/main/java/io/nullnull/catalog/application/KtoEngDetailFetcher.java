package io.nullnull.catalog.application;

import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;

/** Infrastructure port for the single approved EngService2 detail operation (BA-086). */
public interface KtoEngDetailFetcher {

    /** Fails before a collector run is opened when the runtime is not safe to make a call. */
    void requireConfigured();

    /** {@code request} names the English dataset's own content id, never the Korean one. */
    CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request);

    /** Release identifier retained in the safe audit ledger, never a credential. */
    String releaseVersion();
}
