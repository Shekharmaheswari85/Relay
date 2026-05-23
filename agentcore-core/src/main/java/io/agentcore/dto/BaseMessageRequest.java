/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Base request body for {@code POST /sessions/{sessionId}/messages} — delivers a
 * follow-up message to an active agent session.
 *
 * <p>Carries the text the caller wants the agent to process next.  The response is
 * streamed back as Server-Sent Events (SSE) over the same HTTP request.
 *
 * <p>This class uses {@code @Builder} rather than {@code @SuperBuilder} because it
 * is not designed for inheritance; prefer {@link SendMessageRequestDTO} as the
 * concrete type in application code.  If domain-specific fields are needed, extend
 * {@code BaseCreateSessionRequest} instead and embed extra context there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaseMessageRequest {

    /**
     * Carries the text of the message to deliver to the agent.  This is the
     * primary user turn that the LLM pipeline processes.  Required — must be
     * non-null and non-blank for the agent to produce a meaningful response.
     */
    private String content;

    /**
     * Carries an optional message-role hint that adjusts how the framework
     * injects the message into the conversation history.  Accepted values are
     * {@code "user"} (default when absent), {@code "system"} (injects into the
     * system-prompt prefix), and {@code "confirmation"} (signals that this
     * message carries a user's yes/no answer to a pending confirmation gate).
     * Optional.
     */
    private String type;
}
