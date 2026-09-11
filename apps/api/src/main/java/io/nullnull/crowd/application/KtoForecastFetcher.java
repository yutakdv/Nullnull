package io.nullnull.crowd.application;

import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import java.util.concurrent.CompletableFuture;

/** Infrastructure port for the one reviewed KTO {@code tatsCnctrRatedList} operation. */
public interface KtoForecastFetcher {

    /** Fails before a collector run is opened when the endpoint or decoding key is not approved. */
    void requireConfigured();

    CompletableFuture<ProviderResponse> fetch(KtoForecastRequest request);

    /** Safe release identifier retained by the audit ledger; it is never a credential. */
    String releaseVersion();
}
