package io.nullnull.catalog.infrastructure.kto;

/**
 * Renews the forecast of every demo place whose stored set lapses within PT12H (KtoDemoRefresh).
 *
 * <pre>
 * NULLNULL_KTO_FORECAST_SMOKE_APPROVED=true NULLNULL_DEMO_PLACES=126508:12,... ./gradlew ktoDemoForecastRefresh
 * </pre>
 *
 * <p>A separate main from the detail one so each schedule names its own LOADER_MAIN and carries its own
 * approval. Run it after the detail refresh: a forecast request is built from a fresh detail snapshot.
 */
public final class KtoDemoForecastRefreshMain {

    private KtoDemoForecastRefreshMain() {
    }

    public static void main(String[] args) {
        KtoDemoRefreshCommand.run(KtoDemoRefresh.Mode.FORECAST, System.getenv());
    }
}
