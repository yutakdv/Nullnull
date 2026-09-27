package io.nullnull.trip;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Scheduled items retain the actual dated hours evidence after the draft preview is gone. */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("A-09 trip item hours continuity")
class TripItemHoursContinuityIT {

    private static final LocalDate DAY_ONE = LocalDate.parse("2026-10-04");
    private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean CatalogHoursQuery hours;

    private final List<UUID> owners = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();

    @AfterEach
    void removeOnlyOwnRows() {
        for (UUID owner : owners) {
            jdbc.update("DELETE FROM trips WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", owner);
        }
        OwnedRows.remove(jdbc, "places", places);
    }

    @Test
    @DisplayName("A-09 current OPEN, missing UNKNOWN and later CLOSED are projected in one batched hours read")
    void scheduledDaysKeepVerifiedAndUnknownHoursDistinct() throws Exception {
        UUID known = place("영업 확인 장소");
        UUID unverified = place("영업 미확인 장소");
        SessionService.Bootstrap owner = sessions.bootstrap(null, null, null);
        owners.add(owner.owner.id());
        Cookie cookie = new Cookie("__Host-nullnull_session", owner.cookie);

        String created = mvc.perform(post("/api/v1/trips")
                        .cookie(cookie)
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "hours-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"" + DAY_ONE + "\",\"endDate\":\"" + DAY_TWO
                                + "\",\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[],\"seedItems\":["
                                + "{\"placeId\":\"" + known + "\",\"date\":\"" + DAY_ONE
                                + "\",\"position\":0},"
                                + "{\"placeId\":\"" + unverified + "\",\"date\":\"" + DAY_TWO
                                + "\",\"position\":0}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days[0].items[0].hoursState").value("UNKNOWN"))
                .andExpect(jsonPath("$.days[1].items[0].hoursState").value("UNKNOWN"))
                .andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        UUID observed = observation(known);
        window(observed, DAY_ONE, "OPEN", LocalTime.of(9, 0), LocalTime.of(18, 0));

        clearInvocations(hours);
        mvc.perform(get("/api/v1/trips/" + tripId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[0].hoursState").value("OPEN"))
                .andExpect(jsonPath("$.days[1].items[0].hoursState").value("UNKNOWN"))
                .andExpect(jsonPath("$.days[0].items[0].place.id").value(known.toString()))
                .andExpect(jsonPath("$.days[1].items[0].place.id").value(unverified.toString()));
        verify(hours).windowsForAll(argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(known, unverified))),
                eq(DAY_ONE), eq(DAY_TWO), any());
        verify(hours, never()).windowsFor(any(), any(), any(), any());

        window(observation(unverified), DAY_TWO, "CLOSED", null, null);
        mvc.perform(get("/api/v1/trips/" + tripId).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[0].hoursState").value("OPEN"))
                .andExpect(jsonPath("$.days[1].items[0].hoursState").value("CLOSED"))
                .andExpect(jsonPath("$.days[1].items[0].place.id").value(unverified.toString()));
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code,
                                    status, created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, now, now);
        places.add(id);
        return id;
    }

    private UUID observation(UUID place) {
        UUID id = UUID.randomUUID();
        Instant observed = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO place_hours_observations
                    (id, place_id, source_code, source_registry_version, outcome, observed_at,
                     evidence_url, stale_at, created_at)
                VALUES (?, ?, 'NULLNULL_CURATED_HOURS', 1, 'OBSERVED', ?,
                        'https://royal.cha.go.kr/example/hours', ?, ?)
                """, id, place, Timestamp.from(observed),
                Timestamp.from(observed.plus(30, ChronoUnit.DAYS)), Timestamp.from(observed));
        return id;
    }

    private void window(UUID observation, LocalDate date, String state, LocalTime opens, LocalTime closes) {
        jdbc.update("""
                INSERT INTO place_hours_windows (id, observation_id, effective_on, state, opens_at, closes_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), observation, java.sql.Date.valueOf(date), state,
                opens == null ? null : java.sql.Time.valueOf(opens),
                closes == null ? null : java.sql.Time.valueOf(closes));
    }
}
