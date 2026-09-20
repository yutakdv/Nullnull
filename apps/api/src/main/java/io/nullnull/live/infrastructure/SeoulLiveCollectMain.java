package io.nullnull.live.infrastructure;

import io.nullnull.OperationsContext;
import io.nullnull.OperationsPlan;
import io.nullnull.live.application.SeoulLiveAreaGateway;
import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.context.ConfigurableApplicationContext;

/** Collects one reviewed Seoul area into the normal collector ledger and live snapshot store. */
public final class SeoulLiveCollectMain {

    private SeoulLiveCollectMain() {
    }

    public static void main(String[] args) {
        try {
            String areaName = System.getenv("NULLNULL_SEOUL_AREA_NAME");
            if (areaName == null || areaName.isBlank()) {
                throw new IllegalArgumentException("NULLNULL_SEOUL_AREA_NAME is required");
            }
            try (ConfigurableApplicationContext context = OperationsContext.start(OperationsContext.Access.WRITE)) {
                if (!collect(areaName, context.getBean(SeoulLiveAreaGateway.class)::collect, System.out)) {
                    throw new IllegalStateException("Seoul live observation was already stale");
                }
            }
        } catch (RuntimeException failure) {
            System.out.println("seoul_live_collect_failed reason=" + OperationsPlan.failureReason(failure));
            throw failure;
        }
    }

    static boolean collect(String areaName,
            Function<String, CompletableFuture<SeoulLiveAreaGateway.Collection>> collector, PrintStream out) {
        SeoulLiveAreaGateway.Collection collected = collector.apply(areaName).join();
        if (!collected.accepted()) {
            throw new IllegalStateException("Seoul live observation was refused");
        }
        out.println(collected.live() ? "seoul_live_collect live=true" : "seoul_live_collect stale=true");
        return collected.live();
    }
}
