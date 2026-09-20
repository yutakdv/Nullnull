package io.nullnull.optimization.infrastructure.kakao;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the Kakao Mobility settings. There is no startup guard here on purpose: route optimization is
 * P1 and its capability is off, so a deployment with no {@code KAKAO_REST_API_KEY} is the normal one
 * rather than a misconfigured one. The credential is required at the moment a route is asked for
 * ({@link KakaoRouteProperties#requireConfigured(boolean)}), which is the first moment its absence
 * means anything.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoRouteProperties.class)
public class KakaoRouteConfiguration {
}
