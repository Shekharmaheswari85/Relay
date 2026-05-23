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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * JSON structure served at {@code /.well-known/agent.json} for Agent-to-Agent (A2A) discovery.
 *
 * <p>An Agent Card advertises an agent's identity, endpoint, supported capabilities, and
 * authentication requirements to other agents or orchestration layers. Remote callers read the
 * card to determine whether the agent can handle a task before opening a session.
 *
 * <p>This follows the emerging <a href="https://google.github.io/A2A/">Google A2A protocol</a>
 * agent card schema. The {@link AgentCardController} serves this object at the well-known URL
 * when {@code agent.a2a.enabled=true}.
 *
 * <h3>Example card JSON</h3>
 * <pre>{@code
 * {
 *   "name": "Order Agent",
 *   "description": "Handles order placement, tracking, and cancellation",
 *   "url": "https://order-agent.example.com",
 *   "version": "1.2.0",
 *   "capabilities": {
 *     "streaming": true,
 *     "stateTransitions": true
 *   },
 *   "authentication": {
 *     "schemes": ["Bearer"]
 *   },
 *   "skills": [
 *     { "id": "place-order", "name": "Place Order",
 *       "description": "Creates a new customer order" },
 *     { "id": "track-order", "name": "Track Order",
 *       "description": "Returns current shipment status for an order" }
 *   ]
 * }
 * }</pre>
 *
 * <h3>Configuration</h3>
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
 * @see AgentCardController
 * @see AgentCardProperties
 * @see AgentSkill
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentCard {

    /** Human-readable agent name (e.g., {@code "Order Agent"}). */
    String name;

    /** One-paragraph description of what this agent does. */
    String description;

    /**
     * Base URL where the agent's REST API is reachable.
     * Remote callers append {@code /sessions} and {@code /sessions/{id}/messages} to this URL.
     */
    String url;

    /** Semantic version string (e.g., {@code "1.2.0"}). */
    String version;

    /** Capability flags advertised to remote callers. */
    Capabilities capabilities;

    /** Authentication requirements for remote callers. */
    Authentication authentication;

    /** List of skills this agent can perform. */
    @Builder.Default
    List<AgentSkill> skills = List.of();

    /**
     * Advertised capability flags.
     */
    @Value
    @Builder
    @Jacksonized
    public static class Capabilities {

        /**
         * {@code true} when the agent's {@code /sessions/{id}/messages} endpoint returns
         * a {@code text/event-stream} SSE response rather than a blocking JSON body.
         */
        boolean streaming;

        /**
         * {@code true} when the agent maintains session state across multiple turns
         * (i.e., it persists a {@code BaseAgentSession}).
         */
        boolean stateTransitions;
    }

    /**
     * Authentication descriptor for remote callers.
     */
    @Value
    @Builder
    @Jacksonized
    public static class Authentication {

        /**
         * List of supported authentication schemes, e.g. {@code ["Bearer", "ApiKey"]}.
         * Callers must provide credentials matching one of these schemes via an
         * {@link A2AAuthContributor} bean.
         */
        @Builder.Default
        List<String> schemes = List.of();
    }
}
