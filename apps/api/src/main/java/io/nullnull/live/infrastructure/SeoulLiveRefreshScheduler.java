package io.nullnull.live.infrastructure;

import io.nullnull.OperationsPlan;
import io.nullnull.live.application.SeoulLiveAreaGateway;
import io.nullnull.live.infrastructure.persistence.JdbcSeoulLiveRefreshClaim;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Refreshes the one reviewed pilot area from the already-running API service. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "nullnull.live", name = "schedule-enabled", havingValue = "true")
public class SeoulLiveRefreshScheduler {

    // Seoul's published area identifier POI101 is named 서울숲공원. This is the provider's exact
    // endpoint name, not a place-to-area mapping decision; that separate decision needs a plan.
    private static final String AREA_NAME = "서울숲공원";

    private final JdbcSeoulLiveRefreshClaim claims;
    private final SeoulLiveAreaGateway gateway;

    public SeoulLiveRefreshScheduler(JdbcSeoulLiveRefreshClaim claims, SeoulLiveAreaGateway gateway) {
        this.claims = claims;
        this.gateway = gateway;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 120000)
    public void refresh() {
        try {
            if (claims.claim(AREA_NAME)) {
                SeoulLiveCollectMain.collect(AREA_NAME, gateway::collect, System.out);
            }
        } catch (RuntimeException failure) {
            // No provider body, request URL, secret, or pasted text enters the log.
            System.out.println("seoul_live_collect_failed reason=" + OperationsPlan.failureReason(failure));
        }
    }
}
