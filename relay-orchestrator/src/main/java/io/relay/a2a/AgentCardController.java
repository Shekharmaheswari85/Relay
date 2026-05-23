/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the agent's {@link AgentCard} at the standard A2A discovery endpoint
 * {@code GET /.well-known/agent.json}.
 *
 * <p>This controller is only activated when {@code agent.a2a.enabled=true}. It allows remote
 * agents and orchestration layers to discover this agent's identity, URL, skills, and
 * authentication requirements without any prior coordination.
 *
 * <h3>Enable via configuration</h3>
 * <pre>{@code
 * agent:
 *   a2a:
 *     enabled: true
 *     name: "Order Agent"
 *     description: "Handles order-related queries"
 *     url: "https://order-agent.example.com"
 *     version: "1.0.0"
 *     skills:
 *       - id: place-order
 *         name: Place Order
 *         description: Creates a new customer order
 * }</pre>
 *
 * <h3>Discovery flow</h3>
 * <ol>
 *   <li>Remote orchestrator fetches {@code GET https://order-agent.example.com/.well-known/agent.json}</li>
 *   <li>Receives this agent's {@link AgentCard} as JSON</li>
 *   <li>Uses the card's {@code url}, {@code skills}, and {@code authentication} to decide
 *       whether and how to call this agent</li>
 *   <li>Creates a session via {@code POST {url}/sessions} with appropriate auth headers</li>
 * </ol>
 *
 * @see AgentCard
 * @see AgentCardProperties
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentCardProperties.class)
@ConditionalOnProperty(prefix = "agent.a2a", name = "enabled", havingValue = "true")
public class AgentCardController {

    private final AgentCardProperties properties;

    /**
     * Returns this agent's card as JSON.
     *
     * @return the agent card, populated from {@code agent.a2a.*} configuration
     */
    @GetMapping(value = "/.well-known/agent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard getAgentCard() {
        log.debug("Agent card requested");
        return properties.toCard();
    }
}
