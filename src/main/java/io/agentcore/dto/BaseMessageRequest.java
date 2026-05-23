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
