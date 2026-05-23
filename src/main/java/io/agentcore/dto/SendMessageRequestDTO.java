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
 * Concrete request body for {@code POST /sessions/{sessionId}/messages} — delivers a
 * follow-up message to an active agent session and receives the response as a
 * Server-Sent Event stream.
 *
 * <p>This is the default concrete type accepted by
 * {@link io.agentcore.web.BaseAgentController#sendMessage}.  Agents that need
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
