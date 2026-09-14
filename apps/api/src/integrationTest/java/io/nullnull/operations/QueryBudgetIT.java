package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.CountingDataSource;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-070-T3: what a request of a known size costs, stated as structure rather than as time.
 *
 * <p>The card's own safety boundary forbids recording a target as a measurement and says to keep a
 * noisy CI runner's numbers away from an SLO. A p95 asserted here would be both of those, so this
 * measures what CI can state honestly: how many statements and pooled connections one request takes,
 * and whether either grows with the amount of data it reads.
 *
 * <p><b>Growth is the assertion, not the number.</b> A fixed budget would be a constant nobody
 * measured - pick 7 and the test passes until someone adds an eighth honest statement, then gets
 * raised to 8 and has asserted nothing. That the cost does not change when the rows triple is a
 * property of the code, and it is the property an N+1 breaks.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=budget-cursor-secret-that-is-long-enough-abc"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        QueryBudgetIT.Counting.class})
@DisplayName("BA-070 a request's cost does not grow with the rows it reads")
class QueryBudgetIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @TestConfiguration
    static class Counting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(source)
                            : bean;
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM place_localizations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-070-T3 nine times the rows does not cost nine times the statements")
    void costIsBoundedAndDoesNotGrowWithTheData() throws Exception {
        CountingDataSource counter = (CountingDataSource) dataSource;
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");

        for (int at = 0; at < 5; at++) {
            place("예산 시험 장소 " + at);
        }
        // Reset AFTER seeding. The first version of this test did not, and measured its own fixture:
        // two inserts per place, each taking a connection, so the "cost" tripled with the data while
        // the request under test had not changed at all. A counter that spans the setup is measuring
        // the test.
        counter.reset();
        search(owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5));
        CountingDataSource.Counts small = counter.reset();

        for (int at = 5; at < 45; at++) {
            place("예산 시험 장소 " + at);
        }
        counter.reset();
        search(owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(45));
        CountingDataSource.Counts large = counter.reset();

        // The request actually did something, or a cost of zero would compare equal to a cost of zero.
        assertThat(small.statements()).as("the small request must have run statements").isPositive();
        assertThat(small.connections()).isPositive();

        // The bound comes from what an N+1 IS, not from a number somebody picked: hydrating each row
        // with its own query costs at least one extra statement per extra row. So forty more rows
        // must cost fewer than forty more statements. Measured today it costs one - the two sizes are
        // 9 and 10 - and a fixed budget of 9 would be a constant nobody measured, raised to 10 the
        // first time an honest statement is added and asserting nothing thereafter.
        // The bound comes from what an N+1 IS. Hydrating each row with its own query costs one extra
        // statement per row, so the larger call pays 45 and the smaller 5 and the DIFFERENCE is the
        // forty rows between them - exactly the row count. Asserting "fewer than forty" therefore put
        // the defect precisely on the threshold, where rounding decides the verdict; measured, the
        // N+1 landed one statement inside and the test stayed green. Half a statement per added row
        // is the same derivation with the boundary moved off the defect: no per-row query can grow
        // that slowly, and today's honest growth is 1.
        int addedRows = 45 - 5;
        assertThat(large.statements() - small.statements())
                .as("an N+1 grows one statement per row; %s rows were added", addedRows)
                .isLessThan(addedRows / 2);
        assertThat(large.connections() - small.connections())
                .as("a request is one unit of work however many rows it reads")
                .isLessThan(addedRows / 2);
    }

    private org.springframework.test.web.servlet.ResultActions search(SessionService.Bootstrap owner)
            throws Exception {
        return mvc.perform(post("/api/v1/places/search")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"query\":\"예산 시험 장소\",\"locale\":\"ko-KR\",\"limit\":50}"));
    }

    private void place(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, name, Timestamp.from(NOW));
    }
}
