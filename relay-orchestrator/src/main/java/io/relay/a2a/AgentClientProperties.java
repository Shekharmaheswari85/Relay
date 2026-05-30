/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for outbound A2A (Agent-to-Agent) client connections.
 *
 * <p>Each entry under {@code relay.a2a.clients} defines a named remote agent that this
 * agent can call. The logical name (map key) is passed to {@link A2AAuthContributor} beans
 * so they can apply the correct credentials per target.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * relay:
 *   a2a:
 *     clients:
 *       inventory-agent:
 *         url: "https://inventory-agent.example.com"
 *         base-path: "/api/agent"
 *         response-timeout: 60s
 *       fulfillment-agent:
 *         url: "https://fulfillment-agent.example.com"
 *         base-path: "/api/agent"
 *         response-timeout: 120s
 * }</pre>
 *
 * <p>Auth credentials are <em>not</em> configured here — they are injected at runtime by
 * {@link A2AAuthContributor} beans so that secrets stay out of application config files.
 *
 * @see AgentClient
 * @see AgentClientRegistry
 * @see A2AAuthContributor
 */
@Data
@ConfigurationProperties(prefix = "relay.a2a")
public class AgentClientProperties {

    /**
     * Maximum time to wait for a TCP connection to be established for all A2A requests.
     * This timeout applies to the shared singleton {@link io.relay.a2a.A2AHttpClient}.
     * Default: {@code 10s}.
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * Map of logical agent name → client configuration.
     * The key is used in {@link AgentClientRegistry#get(String)} and passed to
     * {@link A2AAuthContributor#contribute(org.springframework.http.HttpHeaders, String)}.
     */
    private Map<String, ClientConfig> clients = new LinkedHashMap<>();

    /**
     * Per-remote-agent connection configuration.
     */
    @Data
    public static class ClientConfig {

        /**
         * Base URL of the remote agent (no trailing slash).
         * Example: {@code "https://inventory-agent.example.com"}
         */
        private String url;

        /**
         * Path prefix prepended to all API calls.
         * Default: {@code "/api/agent"}.
         * Session creation becomes {@code POST {url}{basePath}/sessions}.
         */
        private String basePath = "/api/agent";

        /**
         * Maximum time to wait for the remote agent to complete an SSE stream.
         * Default: {@code 120s}. Set higher for long-running agents.
         */
        private Duration responseTimeout = Duration.ofSeconds(120);

        /**
         * Maximum time to wait for a TCP connection to be established.
         * Default: {@code 10s}.
         */
        private Duration connectTimeout = Duration.ofSeconds(10);
    }
}
