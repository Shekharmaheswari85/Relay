/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /sessions/{sessionId}/confirm} — delivers a user's
 * confirmation or rejection decision for a pending mutation tool call.
 *
 * <h3>Protocol flow</h3>
 * <ol>
 *   <li>The agent pipeline detects a mutation tool call (annotated with
 *       {@code @AgentTool(category = MUTATION)}).</li>
 *   <li>{@code ConfirmationGateAdvisor} intercepts the call, finds no existing
 *       confirmation, and returns a {@code confirmation_required} SSE event containing
 *       the pending tool name.</li>
 *   <li>The UI displays a confirmation prompt to the user.</li>
 *   <li>The user approves or rejects — the UI POSTs to
 *       {@code /sessions/{sessionId}/confirm} with this request body.</li>
 *   <li>The controller syntheses a confirmation signal message and runs the agent
 *       pipeline again. {@code ConfirmationGateAdvisor} detects the signal, enriches
 *       the context with {@code user_confirmed=true} (or returns a rejection response),
 *       and the pipeline continues or cancels accordingly.</li>
 * </ol>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * POST /api/my-agent/sessions/sess-abc123/confirm
 * Content-Type: application/json
 * Accept: text/event-stream
 *
 * {
 *   "toolName": "deleteUser",
 *   "confirmed": true
 * }
 * }</pre>
 *
 * <p>Related runtime components include
 * {@code io.relay.advisor.ConfirmationGateAdvisor} and
 * {@code io.relay.web.BaseAgentController}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmMutationRequest {

    /**
     * The canonical name of the mutation tool that is pending confirmation.
     * This must match the tool's {@code @Tool(name = ...)} value exactly.
     * Required — must be non-null and non-blank.
     */
    private String toolName;

    /**
     * {@code true} if the user approves the pending mutation and the operation
     * should proceed; {@code false} to cancel and return a rejection response.
     */
    private boolean confirmed;
}
