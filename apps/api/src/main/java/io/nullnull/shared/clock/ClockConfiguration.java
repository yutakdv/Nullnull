package io.nullnull.shared.clock;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single injectable clock. Application and domain code never call {@code Instant.now()}
 * directly; the architecture test enforces this for the recommendation package.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
