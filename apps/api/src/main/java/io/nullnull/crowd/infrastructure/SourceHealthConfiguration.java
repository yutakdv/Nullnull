package io.nullnull.crowd.infrastructure;

import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.ReadinessProbe;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SourceHealthConfiguration {

    @Bean
    ReadinessProbe ktoKorServiceHealth(SourceRegistryStore store) {
        return new SourceHealth("KTO_KOR_SERVICE_2", store);
    }

    @Bean
    ReadinessProbe ktoForecastHealth(SourceRegistryStore store) {
        return new SourceHealth("KTO_CONCENTRATION_FORECAST", store);
    }

    @Bean
    ReadinessProbe ktoRelatedHealth(SourceRegistryStore store) {
        return new SourceHealth("KTO_RELATED_PLACES", store);
    }

    @Bean
    ReadinessProbe seoulCitydataHealth(SourceRegistryStore store) {
        return new SourceHealth("SEOUL_CITYDATA", store);
    }

    @Bean
    ReadinessProbe demoReplayHealth(SourceRegistryStore store) {
        return new SourceHealth("DEMO_REPLAY", store);
    }

    @Bean
    ReadinessProbe nullnullCatalogRuleHealth(SourceRegistryStore store) {
        return new SourceHealth("NULLNULL_CATALOG_RULE", store);
    }
}
