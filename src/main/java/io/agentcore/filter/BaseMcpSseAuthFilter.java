/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.filter;

import java.io.IOException;
import java.util.Objects;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract servlet {@link Filter} that guards MCP SSE endpoints using a shared-secret
 * scheme, and serves as the extension point for teams that need custom authentication logic.
 *
 * <p>The threat this filter mitigates is unauthorized access to the MCP SSE endpoint,
 * which Spring AI exposes for all registered {@code ToolCallbackProvider} beans. Without a
 * filter in place, any caller that can reach the endpoint can invoke every registered tool,
 * including read-only discovery tools. For mutation tools an additional session-context
 * guard at the {@code ToolCallback} layer is always active regardless of this filter; see
 * {@link io.agentcore.aspect.BaseMcpCallInterceptor}.
 *
 * <h3>Auth model</h3>
 * <ul>
 *   <li>When {@link #getSharedSecret()} returns a non-blank value, every request whose
 *       path starts with {@link #getPathPrefix()} must supply that value in the header
 *       returned by {@link #getSecretHeader()}. Requests that omit or mismatch the header
 *       receive {@code 401 Unauthorized} and never reach the MCP server.</li>
 *   <li>When {@link #getSharedSecret()} returns {@code null} or blank, all requests to the
 *       configured path prefix pass through without authentication (fully public mode).</li>
 * </ul>
 *
 * <h3>Extending</h3>
 * <pre>{@code
 * @Component
 * @Order(-100)
 * public class AppMcpSseAuthFilter extends BaseMcpSseAuthFilter {
 *
 *     @Value("${agent.mcp.sse.shared-secret:}")
 *     private String sharedSecret;
 *
 *     @Override
 *     protected String getSharedSecret() {
 *         return sharedSecret;
 *     }
 *
 *     @Override
 *     protected String getPathPrefix() {
 *         return "/mcp";
 *     }
 * }
 * }</pre>
 *
 * <p>For the out-of-the-box configuration-driven implementation, see {@link McpSseAuthFilter}.
 *
 * @see McpSseAuthFilter
 * @see io.agentcore.aspect.BaseMcpCallInterceptor
 */
@Slf4j
public abstract class BaseMcpSseAuthFilter implements Filter {

    private static final String DEFAULT_PATH_PREFIX = "/mcp";
    private static final String SECRET_HEADER = "X-MCP-Secret";

    /**
     * Returns the shared secret that incoming requests must present.
     *
     * <p>When this method returns a non-blank value, requests must include that value in
     * the header identified by {@link #getSecretHeader()}. When this method returns
     * {@code null} or blank, the filter allows all traffic to the protected path prefix
     * without any credential check.
     *
     * @return the expected shared-secret value, or {@code null}/blank for public mode
     */
    protected abstract String getSharedSecret();

    /**
     * Returns the URL path prefix whose requests this filter guards.
     *
     * <p>Requests whose path does not start with this prefix pass through the filter
     * without any authentication check. Defaults to {@code "/mcp"}.
     *
     * @return the path prefix to guard; never {@code null}
     */
    protected String getPathPrefix() {
        return DEFAULT_PATH_PREFIX;
    }

    /**
     * Returns the HTTP request header name that must carry the shared secret.
     *
     * <p>Defaults to {@code "X-MCP-Secret"}. Override to align with your organization's
     * header naming conventions.
     *
     * @return the header name; never {@code null}
     */
    protected String getSecretHeader() {
        return SECRET_HEADER;
    }

    /**
     * Intercepts each request and enforces the shared-secret check for paths that fall
     * under {@link #getPathPrefix()}.
     *
     * <p>Requests to other paths are forwarded to the next filter unchanged. For guarded
     * paths, the request is forwarded when the secret is not configured or when the
     * {@link #getSecretHeader()} header value matches {@link #getSharedSecret()}. All other
     * guarded requests receive {@code 401 Unauthorized} without invoking the rest of the
     * filter chain.
     *
     * @param request  the incoming servlet request
     * @param response the servlet response
     * @param chain    the downstream filter chain
     * @throws IOException      if an I/O error occurs during response writing
     * @throws ServletException if a servlet error occurs
     */
    @Override
    public void doFilter(
            final ServletRequest request,
            final ServletResponse response,
            final FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        if (!path.startsWith(getPathPrefix())) {
            chain.doFilter(request, response);
            return;
        }

        String secret = getSharedSecret();
        boolean secretConfigured = secret != null && !secret.isBlank();
        if (!secretConfigured) {
            // No secret configured — fully public
            chain.doFilter(request, response);
            return;
        }

        // Secret configured — enforce it
        String configuredSecret = Objects.requireNonNull(secret, "Secret must not be null when configured");
        String provided = httpRequest.getHeader(
                Objects.requireNonNull(getSecretHeader(), "Secret header must not be null"));
        if (configuredSecret.equals(provided)) {
            log.debug("BaseMcpSseAuthFilter: shared-secret accepted for {}", path);
            chain.doFilter(request, response);
            return;
        }

        log.warn("BaseMcpSseAuthFilter: UNAUTHORIZED path={} — invalid or missing {} header",
                path, getSecretHeader());
        httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
