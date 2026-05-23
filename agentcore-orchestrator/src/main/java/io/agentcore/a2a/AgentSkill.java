/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
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
