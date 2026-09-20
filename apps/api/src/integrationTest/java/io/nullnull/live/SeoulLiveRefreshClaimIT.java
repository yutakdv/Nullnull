package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.live.infrastructure.persistence.JdbcSeoulLiveRefreshClaim;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeoulLiveRefreshClaimIT {

    @Autowired JdbcSeoulLiveRefreshClaim claim;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-091-T24 복수 API 작업이 같은 서울 구역을 동시에 수집하지 않는다")
    void oneClaimWinsAcrossConcurrentWorkers() throws Exception {
        String areaName = "claim-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> contenders = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                contenders.add(() -> { start.await(); return claim.claim(areaName); });
            }
            var results = contenders.stream().map(workers::submit).toList();
            start.countDown();
            long winners = 0;
            for (var result : results) {
                if (result.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
            assertThat(claim.claim(areaName)).isFalse();
        } finally {
            jdbc.update("DELETE FROM seoul_live_refresh_claims WHERE area_name = ?", areaName);
        }
    }
}
