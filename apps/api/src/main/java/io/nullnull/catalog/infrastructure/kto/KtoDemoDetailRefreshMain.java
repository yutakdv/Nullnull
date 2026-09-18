package io.nullnull.catalog.infrastructure.kto;

/**
 * Renews the detailCommon2 snapshot of every demo place whose snapshot lapses within P2D, and maps it to
 * its canonical place (KtoDemoRefresh).
 *
 * <pre>
 * NULLNULL_KTO_SMOKE_APPROVED=true NULLNULL_DEMO_PLACES=126508:12,... ./gradlew ktoDemoDetailRefresh
 * </pre>
 *
 * <p>The first run over a new list also creates the canonical places the forecast refresh looks up, so
 * it is also how a list is loaded for the first time.
 */
public final class KtoDemoDetailRefreshMain {

    private KtoDemoDetailRefreshMain() {
    }

    public static void main(String[] args) {
        KtoDemoRefreshCommand.run(KtoDemoRefresh.Mode.DETAIL, System.getenv());
    }
}
