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

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for the agent's A2A (Agent-to-Agent) identity card.
 *
 * <p>Set {@code agent.a2a.enabled=true} to activate the {@link AgentCardController}, which
 * serves the card at {@code /.well-known/agent.json} so that remote orchestrators can discover
 * this agent's capabilities without prior knowledge.
 *
 * <h3>Minimal configuration</h3>
 * <pre>{@code
 * agent:
 *   a2a:
 *     enabled: true
 *     name: "Order Agent"
 *     description: "Handles order placement, tracking, and cancellation"
 *     url: "https://order-agent.example.com"
 *     version: "1.0.0"
 *     skills:
 *       - id: place-order
 *         name: Place Order
 *         description: Creates a new customer order and returns an order ID
 *         tags: [order, commerce, write]
 *       - id: track-order
 *         name: Track Order
 *         description: Returns current shipment status for an order
 *         tags: [order, commerce, read]
 * }</pre>
 *
 * @see AgentCard
 * @see AgentCardController
 */
@Data
@ConfigurationProperties(prefix = "agent.a2a")
public class AgentCardProperties {

    /**
     * Whether to activate the {@code /.well-known/agent.json} discovery endpoint.
     * Default: {@code false}.
     */
    private boolean enabled = false;

    /**
     * Human-readable agent name displayed in discovery UIs (e.g., {@code "Order Agent"}).
     */
    private String name;

    /**
     * One-paragraph description of what this agent does.
     */
    private String description;

    /**
     * Base URL where this agent's REST API is reachable by remote callers.
     * Must not have a trailing slash (e.g., {@code "https://order-agent.example.com"}).
     */
    private String url;

    /**
     * Semantic version of this agent (e.g., {@code "1.0.0"}).
     */
    private String version = "1.0.0";

    /**
     * Whether this agent supports SSE streaming on its message endpoint.
     * Set to {@code false} only when using a blocking response strategy.
     * Default: {@code true}.
     */
    private boolean streaming = true;

    /**
     * Whether this agent maintains session state across turns.
     * Default: {@code true}.
     */
    private boolean stateTransitions = true;

    /**
     * Authentication schemes required by remote callers (e.g., {@code ["Bearer", "ApiKey"]}).
     */
    private List<String> authSchemes = new ArrayList<>();

    /**
     * Skills advertised in the card. Each entry maps to an {@link AgentSkill}.
     */
    private List<SkillProperties> skills = new ArrayList<>();

    /**
     * Converts these properties into an immutable {@link AgentCard} ready to be serialised
     * as JSON.
     *
     * @return the fully constructed agent card
     */
    public AgentCard toCard() {
        List<AgentSkill> agentSkills = skills.stream()
                .map(s -> AgentSkill.builder()
                        .id(s.getId())
                        .name(s.getName())
                        .description(s.getDescription())
                        .tags(s.getTags() != null ? s.getTags() : List.of())
                        .build())
                .toList();

        return AgentCard.builder()
                .name(name)
                .description(description)
                .url(url)
                .version(version)
                .capabilities(AgentCard.Capabilities.builder()
                        .streaming(streaming)
                        .stateTransitions(stateTransitions)
                        .build())
                .authentication(AgentCard.Authentication.builder()
                        .schemes(authSchemes)
                        .build())
                .skills(agentSkills)
                .build();
    }

    /**
     * Per-skill configuration entry.
     */
    @Data
    public static class SkillProperties {

        /** Machine-readable skill ID (kebab-case, e.g., {@code "place-order"}). */
        private String id;

        /** Human-readable skill name (e.g., {@code "Place Order"}). */
        private String name;

        /** One-sentence description of what the skill does. */
        private String description;

        /** Optional topic tags (e.g., {@code ["order", "write"]}). */
        private List<String> tags = new ArrayList<>();
    }
}
