/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Autoconfigured servlet {@link jakarta.servlet.Filter} that guards all {@code /mcp/**} requests
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
 *       {@link io.relay.aspect.BaseMcpCallInterceptor} regardless of this setting.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * relay:
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
 * {@code agent.mcp.sse.enabled=false} and register your own {@link jakarta.servlet.Filter} bean, or
 * extend {@link BaseMcpSseAuthFilter} directly.
 *
 * @see BaseMcpSseAuthFilter
 * @see io.relay.aspect.BaseMcpCallInterceptor
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "relay.mcp.sse", name = "enabled", havingValue = "true", matchIfMissing = true)
@Order(McpSseAuthFilter.FILTER_ORDER)
public class McpSseAuthFilter extends BaseMcpSseAuthFilter {

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

    @Override
    protected String getSharedSecret() {
        return sharedSecret;
    }

    @Override
    protected String getPathPrefix() {
        return pathPrefix;
    }

    @Override
    protected String getSecretHeader() {
        return headerName;
    }
}
