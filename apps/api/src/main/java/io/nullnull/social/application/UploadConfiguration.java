package io.nullnull.social.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The upload collaborators that are plain objects rather than components.
 *
 * <p>{@link ImageSanitiser} takes its ceiling as a number, not as a property name, so that a test
 * can build one at any ceiling it wants to measure - which is what lets BA-082-T7 assert the
 * boundary case without moving configuration. This is where the shipped one gets the configured
 * value.
 */
@Configuration
public class UploadConfiguration {

    @Bean
    public ImageSanitiser imageSanitiser(UploadProperties properties) {
        return new ImageSanitiser(properties.maxLongEdgePixels());
    }
}
