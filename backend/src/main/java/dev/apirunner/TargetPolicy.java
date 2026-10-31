package dev.apirunner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TargetPolicy {
    private final Set<String> origins;

    public TargetPolicy(@Value("${runner.allowed-origins}") String configured) {
        origins =
                Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> origin(URI.create(s)))
                        .collect(Collectors.toUnmodifiableSet());
        Definitions.require(!origins.isEmpty(), "Configure at least one allowed target origin");
    }

    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid target URL");
        }
        Definitions.require(
                uri.getUserInfo() == null && uri.getFragment() == null,
                "URL credentials and fragments are not allowed");
        Definitions.require(
                origins.contains(origin(uri)),
                "Target origin is not allowed. Configure TARGET_ORIGINS and restart the backend.");
        return uri;
    }

    public Set<String> origins() {
        return origins;
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme();
        Definitions.require(
                scheme != null
                        && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                        && uri.getHost() != null,
                "Use an absolute http:// or https:// URL");
        int port =
                uri.getPort() == -1 ? (scheme.equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        Definitions.require(port > 0 && port <= 65535, "Invalid target port");
        return scheme.toLowerCase(java.util.Locale.ROOT)
                + "://"
                + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + ":"
                + port;
    }
}
