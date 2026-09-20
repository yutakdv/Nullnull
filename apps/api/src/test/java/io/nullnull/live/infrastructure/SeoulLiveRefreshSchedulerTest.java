package io.nullnull.live.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.nullnull.live.application.SeoulLiveAreaGateway;
import io.nullnull.live.infrastructure.persistence.JdbcSeoulLiveRefreshClaim;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeoulLiveRefreshSchedulerTest {

    @Test
    @DisplayName("BA-091-T26 심사 종료 시각부터 서울 수집을 시도하지 않는다")
    void judgingExpiryStopsClaimsAndProviderCalls() {
        var claims = mock(JdbcSeoulLiveRefreshClaim.class);
        var gateway = mock(SeoulLiveAreaGateway.class);

        new SeoulLiveRefreshScheduler(claims, gateway,
                Clock.fixed(Instant.parse("2026-10-25T14:59:59Z"), ZoneOffset.UTC)).refresh();

        verifyNoInteractions(claims, gateway);
    }

    @Test
    void theMinuteBeforeExpiryStillChecksTheClaim() {
        var claims = mock(JdbcSeoulLiveRefreshClaim.class);
        var gateway = mock(SeoulLiveAreaGateway.class);

        new SeoulLiveRefreshScheduler(claims, gateway,
                Clock.fixed(Instant.parse("2026-10-25T14:59:58Z"), ZoneOffset.UTC)).refresh();

        verify(claims).claim("서울숲공원");
        verifyNoInteractions(gateway);
    }
}
