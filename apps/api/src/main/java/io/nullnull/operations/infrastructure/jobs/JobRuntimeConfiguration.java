package io.nullnull.operations.infrastructure.jobs;

import io.nullnull.operations.application.JobProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code nullnull.jobs.*}. The properties record validates its own floors in its constructor, so
 * an unusable value (a bare number read as milliseconds, a concurrency of zero) fails the context
 * start instead of producing a queue that looks configured and does nothing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProperties.class)
public class JobRuntimeConfiguration {
}
