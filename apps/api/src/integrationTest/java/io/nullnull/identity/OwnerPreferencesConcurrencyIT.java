package io.nullnull.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.nullnull.identity.application.*;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("identity-safety")
class OwnerPreferencesConcurrencyIT {
    @Autowired SessionService sessions;
    @Autowired OwnerPreferencesService preferences;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean TripLookup trips;
    @Test @DisplayName("BA-011-T2 trip port receives the authenticated owner and rejects foreign or deleted references")
    void ownerBoundTripPort() {
        var a=sessions.bootstrap(null,null,null);var b=sessions.bootstrap(null,null,null);
        var ac=sessions.resolve(a.cookie,false);var bc=sessions.resolve(b.cookie,false);
        // A real row: the port decides POLICY (is this trip this owner's and active), while the
        // database enforces REFERENCE (the id exists and belongs to this owner). Mocking the
        // port does not exempt the write from the second one.
        UUID trip=io.nullnull.testsupport.TripRows.insert(jdbc,ac.ownerId(),java.time.Instant.now());
        when(trips.isActiveOwnedTrip(ac.ownerId(),trip)).thenReturn(true);
        var patch=new PreferencesPatch(null,null,null,true,trip);
        assertThat(preferences.patch(ac,patch).activeTripId()).isEqualTo(trip);
        assertThatThrownBy(() -> preferences.patch(bc,patch)).isInstanceOf(ApiException.class);
        when(trips.isActiveOwnedTrip(ac.ownerId(),trip)).thenReturn(false);
        assertThatThrownBy(() -> preferences.patch(ac,patch)).isInstanceOf(ApiException.class);
        verify(trips,times(2)).isActiveOwnedTrip(ac.ownerId(),trip);
        verify(trips).isActiveOwnedTrip(bc.ownerId(),trip);
    }
    @Test @DisplayName("BA-011-T3 concurrent preference patches preserve the field committed while waiting for the owner lock")
    void lockedReadPreservesConcurrentChange() throws Exception {
        var a=sessions.bootstrap(null,null,null);var ctx=sessions.resolve(a.cookie,false);
        try(var connection=dataSource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var sql=connection.prepareStatement("UPDATE owners SET locale = 'en-US' WHERE id = ?")) {
                sql.setObject(1,ctx.ownerId());sql.executeUpdate();
            }
            var entered=new CountDownLatch(1);
            var future=executor.submit(() -> {
                entered.countDown();return preferences.patch(ctx,new PreferencesPatch(null,"Europe/Paris",null,false,null));
            });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> future.get(350,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { connection.commit(); }
            assertThat(future.get(5,TimeUnit.SECONDS).locale()).isEqualTo("en-US");
            assertThat(preferences.get(ctx).timezone()).isEqualTo("Europe/Paris");
        }
    }
}
