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
 * Concrete request body for {@code POST /sessions/{sessionId}/messages} — delivers a
 * follow-up message to an active agent session and receives the response as a
 * Server-Sent Event stream.
 *
 * <p>This is the default concrete type accepted by
 * {@code io.relay.web.BaseAgentController#sendMessage}. Agents that need
 * extra message-level fields (e.g. a correlation ID or a locale hint) should
 * extend {@link BaseMessageRequest} instead and override
 * {@code BaseAgentController.sendMessage} to accept the richer type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequestDTO {

    /**
     * Carries the text of the user message to deliver to the agent.  This is
     * the primary user turn that the LLM pipeline processes.  Required — must
     * be non-null and non-blank for the agent to produce a meaningful response.
     */
    private String content;

    /**
     * Carries an optional role hint that controls how the framework injects this
     * message into the conversation history.  Accepted values: {@code "user"}
     * (default when absent — normal user turn), {@code "system"} (prepended to
     * the system-prompt prefix), {@code "confirmation"} (signals a yes/no answer
     * to a pending confirmation-gate prompt).  Optional.
     */
    private String type;
}
