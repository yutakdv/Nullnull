package io.nullnull.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * MockMvc does not apply {@code spring.mvc.servlet.path} on its own. This customizer sets the
 * servlet path on every default request so tests use the same full URIs a browser sends
 * ({@code /api/v1/...}) and routing is verified through the real dispatcher mapping.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ServletPathMockMvcConfiguration {

    @Bean
    MockMvcBuilderCustomizer servletPathMockMvcCustomizer(@Value("${spring.mvc.servlet.path}") String servletPath) {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/").servletPath(servletPath));
    }
}
