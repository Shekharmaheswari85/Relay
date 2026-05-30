/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes a single user intent that an agent recognizes and can fulfil.
 *
 * <p>Instances are embedded inside {@link AgentMetadataDTO} and surfaced by the
 * agent registry so that callers can present intent-selection UIs and validate
 * whether the required context fields have been collected before dispatching a
 * session.
 *
 * <p>The {@code LlmIntentRouter} matches incoming user messages against the
 * registered intents and uses {@link #prompt} as the routing instruction when
 * forwarding control to the appropriate sub-agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentIntentDTO {

    /**
     * Carries the stable machine-readable identifier for this intent
     * (e.g. {@code "create-market"}).  Must be unique within the owning agent.
     * Required.
     */
    private String intentId;

    /**
     * Carries the short human-readable label shown in intent-selection UI
     * components (e.g. {@code "Create a new market"}).  Required.
     */
    private String label;

    /**
     * Carries the routing instruction injected into the LLM pipeline when this
     * intent is matched.  Describes what the agent should do next in plain
     * language so that the LLM can proceed without ambiguity.  Required.
     */
    private String prompt;

    /**
     * Carries the names of session context keys that must be populated before
     * this intent can be executed (e.g. {@code ["market", "banner"]}).  The
     * framework uses this list to detect missing context and trigger the
     * clarification flow.  May be empty when no prior context is needed.
     */
    private List<String> requiredContextFields;

    /**
     * Carries the clarification prompt shown to the user when one or more
     * {@link #requiredContextFields} are absent from the session context
     * (e.g. {@code "Which market and banner would you like to configure?"}).
     * Optional — when {@code null} the framework uses a generic default
     * clarification message.
     */
    private String missingContextPrompt;
}
