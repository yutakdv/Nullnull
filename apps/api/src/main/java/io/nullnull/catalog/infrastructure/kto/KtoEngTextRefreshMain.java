package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.OperationsContext;
import io.nullnull.catalog.application.EngTextStore;
import io.nullnull.catalog.application.KtoEngTextRefresh;
import io.nullnull.catalog.application.KtoGatewayException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Refreshes the English text of every owner-linked place once (BA-086). Each line names a place id and
 * what happened to it - the provider's text, the record ids and the key never reach the output.
 */
public final class KtoEngTextRefreshMain {

    private KtoEngTextRefreshMain() {
    }

    public static void main(String[] args) {
        Map<String, String> settings = KtoSmokeEnvironment.load(System.getenv(), Path.of(".env.local"));
        String requestedEnvironment = KtoSmokeEnvironment.environment(settings);
        KtoSmokeEnvironment.sources(System.getenv(), Path.of(".env.local"))
                .forEach(line -> System.out.println("KTO_ENG_TEXT_REFRESH_SETTINGS " + line));
        requirePermittedEnvironment(requestedEnvironment);
        int failed;
        try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE,
                KtoSmokeEnvironment.applying(settings))) {
            requirePermittedEnvironment(context.getEnvironment().getProperty("nullnull.env", requestedEnvironment));
            KtoEngTextRefresh refresh = context.getBean(KtoEngTextRefresh.class);
            failed = refreshAll(refresh.links(), refresh::refresh, System.out);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("English text refresh failed: " + KtoSmokeEnvironment.failureCode(failure));
        }
        if (failed > 0) {
            throw new IllegalStateException("English text refresh failed for " + failed + " link(s)");
        }
    }

    /**
     * One refresh per link. A quarantined or disabled source stops the run: every later call would be refused
     * for the same reason, and each attempt would still open a collector run.
     */
    static int refreshAll(List<EngTextStore.Link> links, Function<EngTextStore.Link, KtoEngTextRefresh.Outcome> refresh,
            PrintStream out) {
        int failed = 0;
        int attempted = 0;
        for (EngTextStore.Link link : links) {
            attempted++;
            try {
                out.println("KTO_ENG_TEXT_REFRESH placeId=" + link.placeId() + " outcome=" + refresh.apply(link));
            } catch (KtoGatewayException failure) {
                failed++;
                out.println("KTO_ENG_TEXT_REFRESH placeId=" + link.placeId() + " failure=" + failure.code());
                if (failure.code() == KtoGatewayException.Code.SOURCE_QUARANTINED
                        || failure.code() == KtoGatewayException.Code.SOURCE_DISABLED) {
                    break;
                }
            }
        }
        out.println("KTO_ENG_TEXT_REFRESH_DONE links=" + links.size() + " attempted=" + attempted + " failed=" + failed);
        return failed;
    }

    static void requirePermittedEnvironment(String environment) {
        if (!"local".equals(environment) && !"staging".equals(environment)) {
            throw new IllegalStateException("the English text refresh is permitted only in local or staging");
        }
    }
}
