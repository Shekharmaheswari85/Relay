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

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Describes a single capability (skill) that an agent can perform.
 *
 * <p>Skills appear inside an {@link AgentCard} and are advertised at
 * {@code /.well-known/agent.json}. Remote callers use them to decide whether
 * an agent is capable of handling a particular task before opening a session.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * AgentSkill skill = AgentSkill.builder()
 *         .id("place-order")
 *         .name("Place Order")
 *         .description("Creates a new customer order and returns an order ID")
 *         .tags(List.of("order", "commerce", "write"))
 *         .build();
 * }</pre>
 *
 * @see AgentCard
 */
@Value
@Builder
@Jacksonized
public class AgentSkill {

    /**
     * Machine-readable identifier for this skill, unique within the agent.
     * Use kebab-case (e.g., {@code "place-order"}, {@code "track-shipment"}).
     */
    String id;

    /**
     * Human-readable name shown in discovery UIs and logs (e.g., {@code "Place Order"}).
     */
    String name;

    /**
     * One-sentence description of what this skill does; used by routing agents to
     * decide whether to delegate here.
     */
    String description;

    /**
     * Optional list of topic tags for filtering (e.g., {@code ["order", "write", "commerce"]}).
     * Null-safe — defaults to an empty list when not set.
     */
    @Builder.Default
    List<String> tags = List.of();
}
