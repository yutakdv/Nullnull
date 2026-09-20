package io.nullnull.crowd.infrastructure.seoul;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds nullnull.seoul.* so the live-area collector has an endpoint and a proxy token. */
@Configuration
@EnableConfigurationProperties(SeoulCityDataProperties.class)
public class SeoulCityDataConfiguration {
}
