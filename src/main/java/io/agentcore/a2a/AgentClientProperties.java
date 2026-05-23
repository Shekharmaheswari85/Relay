/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.a2a;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for outbound A2A (Agent-to-Agent) client connections.
 *
 * <p>Each entry under {@code agent.a2a.clients} defines a named remote agent that this
 * agent can call. The logical name (map key) is passed to {@link A2AAuthContributor} beans
 * so they can apply the correct credentials per target.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * agent:
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
@ConfigurationProperties(prefix = "agent.a2a")
public class AgentClientProperties {

    /**
     * Maximum time to wait for a TCP connection to be established for all A2A requests.
     * This timeout applies to the shared singleton {@link io.agentcore.a2a.A2AHttpClient}.
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
