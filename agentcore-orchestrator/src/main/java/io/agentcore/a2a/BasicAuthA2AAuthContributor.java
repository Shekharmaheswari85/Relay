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
 * {@link A2AAuthContributor} that injects HTTP Basic authentication credentials into
 * outbound A2A calls.
 *
 * <p>The username and password are Base64-encoded by {@link HttpHeaders#setBasicAuth}
 * and written as the standard {@code Authorization: Basic <encoded>} header on every
 * matching outbound request.
 *
 * <h3>Single-agent wiring</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor inventoryAgentBasicAuth(
 *         @Value("${inventory.agent.username}") String username,
 *         @Value("${inventory.agent.password}") String password) {
 *     return new BasicAuthA2AAuthContributor("inventory-agent", username, password);
 * }
 * }</pre>
 *
 * <h3>Multi-agent wiring</h3>
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor serviceBasicAuth(
 *         @Value("${inventory.username}") String invUser,
 *         @Value("${inventory.password}") String invPass,
 *         @Value("${pricing.username}")   String priceUser,
 *         @Value("${pricing.password}")   String pricePass) {
 *     return BasicAuthA2AAuthContributor.forAgents(Map.of(
 *             "inventory-agent", new BasicAuthA2AAuthContributor.Credentials(invUser, invPass),
 *             "pricing-agent",   new BasicAuthA2AAuthContributor.Credentials(priceUser, pricePass)));
 * }
 * }</pre>
 *
 * @see A2AAuthContributor
 * @see StaticBearerTokenA2AAuthContributor
 * @see ApiKeyA2AAuthContributor
 */
@Slf4j
public final class BasicAuthA2AAuthContributor implements A2AAuthContributor {

    /**
     * Username/password pair for a single remote agent.
     *
     * @param username the Basic auth username; must not be null
     * @param password the Basic auth password; must not be null
     */
    public record Credentials(String username, String password) {

        /**
         * @throws NullPointerException if username or password is null
         */
        public Credentials {
            Objects.requireNonNull(username, "username must not be null");
            Objects.requireNonNull(password, "password must not be null");
        }
    }

    private final Map<String, Credentials> credentialsByAgent;

    /**
     * Creates a contributor that injects Basic auth credentials only for calls to
     * {@code agentName}.
     *
     * @param agentName the logical agent name (must match the key in
     *                  {@code agent.a2a.clients}); must not be blank
     * @param username  the Basic auth username; must not be null
     * @param password  the Basic auth password; must not be null
     */
    public BasicAuthA2AAuthContributor(
            final String agentName, final String username, final String password) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        this.credentialsByAgent = Collections.singletonMap(agentName, new Credentials(username, password));
    }

    /**
     * Creates a contributor from a map of {@code agentName → credentials} pairs. Useful
     * when a single Spring bean should cover multiple remote agents.
     *
     * @param credentialsByAgent map of logical agent name → credentials; must not be null
     * @return a new contributor
     */
    public static BasicAuthA2AAuthContributor forAgents(final Map<String, Credentials> credentialsByAgent) {
        Objects.requireNonNull(credentialsByAgent, "credentialsByAgent must not be null");
        return new BasicAuthA2AAuthContributor(new LinkedHashMap<>(credentialsByAgent));
    }

    private BasicAuthA2AAuthContributor(final Map<String, Credentials> credentialsByAgent) {
        this.credentialsByAgent = Collections.unmodifiableMap(credentialsByAgent);
    }

    @Override
    public void contribute(final HttpHeaders headers, final String agentName) {
        Credentials creds = credentialsByAgent.get(agentName);
        if (creds != null) {
            headers.setBasicAuth(creds.username(), creds.password());
            log.debug("Applied Basic auth for A2A agent: {}", agentName);
        }
    }
}
