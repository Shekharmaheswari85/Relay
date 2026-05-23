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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configured servlet {@link Filter} that guards all {@code /mcp/**} requests
 * using a configurable shared-secret header.
 *
 * <p>This is the ready-to-use, configuration-driven implementation of the MCP SSE
 * authentication strategy. It activates automatically in servlet-based Spring Boot
 * applications and requires no code — all behavior is controlled through
 * {@code application.yml} properties.
 *
 * <h3>Auth model</h3>
 * <ul>
 *   <li>When {@code agent.mcp.sse.shared-secret} is set to a non-blank value, every
 *       request to the configured path prefix must present that value in the configured
 *       header. Requests that omit or mismatch the header receive {@code 401 Unauthorized}
 *       and are stopped before reaching the MCP server.</li>
 *   <li>When {@code agent.mcp.sse.shared-secret} is absent or blank, all requests to
 *       the path prefix are permitted without authentication (fully public mode). Mutation
 *       tools remain protected at the {@code ToolCallback} layer via
 *       {@link io.agentcore.aspect.BaseMcpCallInterceptor} regardless of this setting.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * agent:
 *   mcp:
 *     sse:
 *       enabled: true                         # set false to disable this filter entirely
 *       shared-secret: ${MCP_SSE_SECRET:}     # blank = fully public
 *       path-prefix: /mcp                     # default; change to match your MCP mount path
 *       header-name: X-MCP-Secret             # default; change to match your API gateway header
 * }</pre>
 *
 * <h3>Customization</h3>
 * <p>To replace this filter with custom authentication (OAuth2, mTLS, etc.), set
 * {@code agent.mcp.sse.enabled=false} and register your own {@link Filter} bean, or
 * extend {@link BaseMcpSseAuthFilter} directly.
 *
 * @see BaseMcpSseAuthFilter
 * @see io.agentcore.aspect.BaseMcpCallInterceptor
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "agent.mcp.sse", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(McpSseAuthFilter.FILTER_ORDER)
@Slf4j
public class McpSseAuthFilter implements Filter {

    /**
     * Filter order that ensures this filter runs early in the chain, rejecting
     * unauthorized MCP requests before any downstream processing occurs.
     */
    public static final int FILTER_ORDER = -100;

    @Value("${agent.mcp.sse.shared-secret:}")
    private String sharedSecret;

    @Value("${agent.mcp.sse.path-prefix:/mcp}")
    private String pathPrefix;

    @Value("${agent.mcp.sse.header-name:X-MCP-Secret}")
    private String headerName;

    @Value("${agent.mcp.sse.enabled:true}")
    private boolean enabled;

    /**
     * Applies the shared-secret check to each incoming request.
     *
     * <p>Requests to paths outside the configured prefix are forwarded immediately. For
     * guarded paths, the request is forwarded when the filter is disabled, no secret is
     * configured, or the {@code agent.mcp.sse.header-name} header value equals the
     * configured secret. All other requests are rejected with {@code 401 Unauthorized}
     * without invoking the rest of the chain.
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

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();
        if (!path.startsWith(pathPrefix)) {
            chain.doFilter(request, response);
            return;
        }

        boolean secretConfigured = sharedSecret != null && !sharedSecret.isBlank();
        if (!secretConfigured) {
            // No secret configured — fully public
            chain.doFilter(request, response);
            return;
        }

        // Secret configured — enforce it
        String provided = httpRequest.getHeader(
                Objects.requireNonNull(headerName, "Header name must not be null"));
        if (sharedSecret.equals(provided)) {
            log.debug("MCP auth accepted for path={}", path);
            chain.doFilter(request, response);
            return;
        }

        log.warn("MCP auth REJECTED: path={} — invalid or missing {} header", path, headerName);
        httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
