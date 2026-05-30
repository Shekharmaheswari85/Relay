/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

import org.springframework.http.HttpHeaders;

/**
 * SPI for injecting authentication headers into outbound Agent-to-Agent (A2A) calls.
 *
 * <p>When {@link AgentClient} opens a session or sends a message to a remote agent, it
 * calls every registered {@code A2AAuthContributor} bean so that each can add the
 * credentials expected by that remote agent.
 *
 * <h3>Built-in patterns</h3>
 *
 * <p><b>Static Bearer token</b>:
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor inventoryAgentAuth(
 *         @Value("${inventory.agent.token}") String token) {
 *     return (headers, agentName) -> {
 *         if ("inventory-agent".equals(agentName)) {
 *             headers.setBearerAuth(token);
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p><b>API key per agent</b>:
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor fulfillmentAgentAuth() {
 *     return (headers, agentName) -> {
 *         if ("fulfillment-agent".equals(agentName)) {
 *             headers.set("X-Api-Key", System.getenv("FULFILLMENT_API_KEY"));
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p><b>Dynamic OAuth token</b>:
 * <pre>{@code
 * @Bean
 * public A2AAuthContributor oauthContributor(OAuth2AuthorizedClientManager clientManager) {
 *     return (headers, agentName) -> {
 *         OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
 *                 .withClientRegistrationId(agentName)
 *                 .principal("agent-service")
 *                 .build();
 *         OAuth2AuthorizedClient client = clientManager.authorize(req);
 *         if (client != null) {
 *             headers.setBearerAuth(client.getAccessToken().getTokenValue());
 *         }
 *     };
 * }
 * }</pre>
 *
 * <h3>Implementor contract</h3>
 * <ul>
 *   <li>Must be stateless and thread-safe — called concurrently by multiple request threads.</li>
 *   <li>Must not throw checked exceptions — wrap errors in unchecked exceptions if needed.</li>
 *   <li>The {@code agentName} parameter is the key used in {@link AgentClientRegistry}
 *       (e.g., {@code "inventory-agent"}). Use it to apply credentials selectively.</li>
 *   <li>Multiple contributors may be registered; all are called in Spring bean order.</li>
 * </ul>
 *
 * @see AgentClient
 * @see AgentClientRegistry
 */
@FunctionalInterface
public interface A2AAuthContributor {

    /**
     * Contributes authentication headers for an outbound call to the named remote agent.
     *
     * @param headers   the mutable request headers; add credentials here
     * @param agentName the logical name of the target agent (key in {@link AgentClientRegistry})
     */
    void contribute(HttpHeaders headers, String agentName);
}
