package io.nullnull.shared.provider;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exact host allowlists under nullnull.sources.&lt;source-code&gt;.allowed-hosts. */
@ConfigurationProperties(prefix = "nullnull")
public record SourceProviderProperties(Map<String, Source> sources) {

    private static final Pattern HOST = Pattern.compile("(?:[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?|127\\.0\\.0\\.1)");

    public SourceProviderProperties {
        Objects.requireNonNull(sources, "nullnull.sources is required");
        sources = Map.copyOf(sources);
        sources.forEach((code, source) -> {
            if (code.isBlank() || source == null || source.allowedHosts() == null
                    || source.allowedHosts().isEmpty()) {
                throw new IllegalArgumentException("every nullnull.sources entry needs allowed-hosts");
            }
            source.allowedHosts().forEach(host -> {
                if (!HOST.matcher(host).matches()) {
                    throw new IllegalArgumentException("allowed host must be a bare exact hostname");
                }
            });
        });
    }

    public record Source(List<String> allowedHosts) {
        public Source {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        }
    }
}
