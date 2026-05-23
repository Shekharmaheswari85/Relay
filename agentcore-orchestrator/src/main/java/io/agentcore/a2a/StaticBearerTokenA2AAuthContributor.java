/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link A2AAuthContributor} that injects a static Bearer token into outbound A2A calls.
 *
 * <p>Use this when the target remote agent authenticates via a long-lived API token or service
 * account credential that does not rotate automatically. For short-lived tokens (OAuth 2.0
 * client credentials), prefer a custom contributor that refreshes the token on each call.
 *
 * <h3>Single-agent wiring</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor inventoryAgentAuth(
 *         @Value("${inventory.agent.token}") String token) {
 *     return new StaticBearerTokenA2AAuthContributor("inventory-agent", token);
 * }
 * }</pre>
 *
 * <h3>Multi-agent wiring (one token per agent)</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor agentTokenAuth(
 *         @Value("${inventory.agent.token}") String inventoryToken,
 *         @Value("${fulfillment.agent.token}") String fulfillmentToken) {
 *     return StaticBearerTokenA2AAuthContributor.forAgents(Map.of(
 *             "inventory-agent",   inventoryToken,
 *             "fulfillment-agent", fulfillmentToken));
 * }
 * }</pre>
 *
 * @see A2AAuthContributor
 * @see ApiKeyA2AAuthContributor
 * @see BasicAuthA2AAuthContributor
 */
@Slf4j
public final class StaticBearerTokenA2AAuthContributor implements A2AAuthContributor {

    private final Map<String, String> tokensByAgent;

    /**
     * Creates a contributor that injects {@code token} only for calls to {@code agentName}.
     *
     * @param agentName the logical agent name (must match the key in
     *                  {@code agent.a2a.clients}); must not be blank
     * @param token     the Bearer token to inject; must not be blank
     */
    public StaticBearerTokenA2AAuthContributor(final String agentName, final String token) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(token, "token must not be null");
        this.tokensByAgent = Collections.singletonMap(agentName, token);
    }

    /**
     * Creates a contributor from a map of {@code agentName → token} pairs. Useful when
     * a single Spring bean should cover multiple remote agents.
     *
     * @param tokensByAgent map of logical agent name → Bearer token; must not be null or empty
     * @return a new contributor
     */
    public static StaticBearerTokenA2AAuthContributor forAgents(final Map<String, String> tokensByAgent) {
        Objects.requireNonNull(tokensByAgent, "tokensByAgent must not be null");
        return new StaticBearerTokenA2AAuthContributor(new LinkedHashMap<>(tokensByAgent));
    }

    private StaticBearerTokenA2AAuthContributor(final Map<String, String> tokensByAgent) {
        this.tokensByAgent = Collections.unmodifiableMap(tokensByAgent);
    }

    @Override
    public void contribute(final HttpHeaders headers, final String agentName) {
        String token = tokensByAgent.get(agentName);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
            log.debug("Applied static Bearer token for A2A agent: {}", agentName);
        }
    }
}
