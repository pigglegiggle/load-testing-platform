package dev.apirunner;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
public class LocalAccess extends OncePerRequestFilter {
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader("Origin");
        boolean allowed = true;
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                allowed =
                        "http".equals(uri.getScheme())
                                && Set.of("localhost", "127.0.0.1").contains(uri.getHost())
                                && (uri.getPort() == 4200
                                        || uri.getPort() == request.getLocalPort());
            } catch (Exception exception) {
                allowed = false;
            }
        }
        if (!allowed) {
            response.sendError(403, "Use the local application origin");
            return;
        }
        if (request.getContentLengthLong() > 2_097_152) {
            response.sendError(413, "Request body exceeds 2 MiB");
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
