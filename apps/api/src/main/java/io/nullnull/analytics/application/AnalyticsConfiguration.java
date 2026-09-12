package io.nullnull.analytics.application;

import io.nullnull.analytics.domain.EventBatchValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the canonical event schema at startup.
 *
 * <p>{@code contracts/events.schema.json} is packaged from {@code docs/contracts/events.schema.json}
 * by {@code processResources}, so there is one definition in the repository and the runtime enforces
 * that one. Loading it here rather than per request also means a missing or unparsable schema fails
 * the context start: a validator that silently accepted everything because its schema did not load
 * is the failure this endpoint can least afford.
 */
@Configuration
public class AnalyticsConfiguration {

    @Bean
    public EventBatchValidator eventBatchValidator() {
        ClassPathResource resource = new ClassPathResource("contracts/events.schema.json");
        try (InputStream stream = resource.getInputStream()) {
            return new EventBatchValidator(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException missing) {
            throw new IllegalStateException(
                    "the canonical event schema is not on the classpath; processResources must "
                            + "package docs/contracts/events.schema.json", missing);
        }
    }
}
