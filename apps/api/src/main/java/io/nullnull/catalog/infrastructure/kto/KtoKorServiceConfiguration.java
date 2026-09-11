package io.nullnull.catalog.infrastructure.kto;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fails closed only for the contest runtime profile; local and PR fixtures never call KTO. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KtoKorServiceProperties.class)
public class KtoKorServiceConfiguration {

    @Bean
    InitializingBean ktoContestProfileGuard(KtoKorServiceProperties properties) {
        return () -> {
            if (properties.isContestProfile()) {
                properties.requireConfigured(false);
                properties.requireForecastConfigured(false);
            }
        };
    }
}
