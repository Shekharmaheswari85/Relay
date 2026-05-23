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
 * {@link A2AAuthContributor} that injects a static API key into a configurable request header
 * on outbound A2A calls.
 *
 * <p>Use this when the target remote agent authenticates via a custom header such as
 * {@code X-Api-Key}, {@code X-Service-Token}, or a proprietary vendor-specific header.
 * For standard HTTP Bearer authentication, prefer {@link StaticBearerTokenA2AAuthContributor}.
 *
 * <h3>Single-agent wiring</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor fulfillmentAgentAuth(
 *         @Value("${fulfillment.agent.api-key}") String apiKey) {
 *     return new ApiKeyA2AAuthContributor("fulfillment-agent", "X-Api-Key", apiKey);
 * }
 * }</pre>
 *
 * <h3>Multi-agent wiring (same header name, different keys)</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor serviceApiKeyAuth(
 *         @Value("${inventory.api-key}") String inventoryKey,
 *         @Value("${pricing.api-key}") String pricingKey) {
 *     return ApiKeyA2AAuthContributor.forAgents("X-Api-Key", Map.of(
 *             "inventory-agent", inventoryKey,
 *             "pricing-agent",   pricingKey));
 * }
 * }</pre>
 *
 * @see A2AAuthContributor
 * @see StaticBearerTokenA2AAuthContributor
 * @see BasicAuthA2AAuthContributor
 */
@Slf4j
public final class ApiKeyA2AAuthContributor implements A2AAuthContributor {

    /** Common API key header name used by many services. */
    public static final String HEADER_X_API_KEY = "X-Api-Key";

    private final String headerName;
    private final Map<String, String> apiKeysByAgent;

    /**
     * Creates a contributor that injects {@code apiKey} under {@code headerName}
     * only for calls to {@code agentName}.
     *
     * @param agentName  the logical agent name (must match the key in
     *                   {@code agent.a2a.clients}); must not be blank
     * @param headerName the HTTP header to set (e.g., {@code "X-Api-Key"}); must not be blank
     * @param apiKey     the API key value; must not be blank
     */
    public ApiKeyA2AAuthContributor(final String agentName, final String headerName, final String apiKey) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(headerName, "headerName must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.headerName = headerName;
        this.apiKeysByAgent = Collections.singletonMap(agentName, apiKey);
    }

    /**
     * Creates a contributor from a map of {@code agentName → apiKey} pairs that all share the
     * same header name. Useful when a single Spring bean covers multiple remote agents.
     *
     * @param headerName   the HTTP header to set for every matching agent
     * @param apiKeysByAgent map of logical agent name → API key; must not be null
     * @return a new contributor
     */
    public static ApiKeyA2AAuthContributor forAgents(
            final String headerName, final Map<String, String> apiKeysByAgent) {
        Objects.requireNonNull(headerName, "headerName must not be null");
        Objects.requireNonNull(apiKeysByAgent, "apiKeysByAgent must not be null");
        return new ApiKeyA2AAuthContributor(headerName, new LinkedHashMap<>(apiKeysByAgent));
    }

    private ApiKeyA2AAuthContributor(final String headerName, final Map<String, String> apiKeysByAgent) {
        this.headerName = headerName;
        this.apiKeysByAgent = Collections.unmodifiableMap(apiKeysByAgent);
    }

    @Override
    public void contribute(final HttpHeaders headers, final String agentName) {
        String apiKey = apiKeysByAgent.get(agentName);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set(headerName, apiKey);
            log.debug("Applied API key header '{}' for A2A agent: {}", headerName, agentName);
        }
    }
}
