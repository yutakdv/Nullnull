package io.nullnull.testsupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for integration and contract suites (H2/SQLite are not substitutes,
 * docs/engineering/TEST_STRATEGY.md §2). Locally a Testcontainers instance is started; inside
 * the PR Compose gate set {@code NULLNULL_TEST_DATABASE=external} and the usual
 * {@code SPRING_DATASOURCE_*} variables so the suites run against the compose service instead.
 * The image is the same digest-pinned PostgreSQL 17.6 used by compose.yml.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String POSTGRES_IMAGE =
            "postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94";

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "nullnull.test.database", havingValue = "testcontainers",
            matchIfMissing = true)
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(
                DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"));
    }
}
