package io.nullnull.shared.provider;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProviderClientProperties.class, SourceProviderProperties.class})
public class ProviderClientConfiguration {

    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor providerExecutor(ProviderClientProperties properties) {
        return new ThreadPoolExecutor(properties.executorThreads(), properties.executorThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.executorQueueCapacity()),
                Thread.ofPlatform().name("provider-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ProviderHostPolicy providerHostPolicy(@Value("${nullnull.env}") String environment) {
        return new ProviderHostPolicy(environment);
    }

    @Bean
    ProviderHttpClient providerHttpClient(ProviderClientProperties properties,
            SourceProviderProperties sourceProperties, ProviderHostPolicy hostPolicy,
            ThreadPoolExecutor providerExecutor, Clock clock) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        RetryPolicy retry = new RetryPolicy(properties.retry().attempts(),
                properties.retry().baseDelay(), properties.retry().maxDelay(), clock,
                () -> ThreadLocalRandom.current().nextDouble(), duration -> Thread.sleep(duration));
        Map<String, Set<String>> hosts = sourceProperties.sources().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue().allowedHosts())));
        hostPolicy.validate(hosts);
        Map<String, CircuitBreaker> circuits = hosts.keySet().stream().collect(Collectors.toUnmodifiableMap(
                code -> code, code -> new CircuitBreaker(clock, properties.circuit().failureThreshold(),
                        properties.circuit().failureWindow(), properties.circuit().openDuration())));
        return new ProviderHttpClient(client, properties.requestTimeout(), properties.maxResponseBytes(),
                providerExecutor, retry, hosts, circuits, properties.perSourceConcurrency());
    }
}
