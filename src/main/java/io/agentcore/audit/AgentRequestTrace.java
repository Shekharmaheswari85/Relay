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
package io.agentcore.audit;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;

/**
 * Structured audit trace for a single LLM call event.
 * <p>
 * Captures richer context than the raw string-based
 * {@code saveAuditLog(sessionId, eventType, input, output, durationMs)} contract —
 * including token counts, agent name, and an extensible metadata map.
 *
 * <h3>Usage</h3>
 * Override {@link io.agentcore.advisor.BaseAuditAdvisor#saveAuditTrace} to
 * receive this object instead of (or in addition to) the string variant.
 *
 * <pre>{@code
 * @Component
 * public class MyAuditAdvisor extends BaseAuditAdvisor {
 *
 *     @Override
 *     protected void saveAuditTrace(AgentRequestTrace trace) {
 *         myAuditRepo.save(MyAuditDO.builder()
 *                 .sessionId(trace.getSessionId())
 *                 .eventType(trace.getEventType())
 *                 .userInput(trace.getUserInput())
 *                 .assistantOutput(trace.getAssistantOutput())
 *                 .inputTokens(trace.getInputTokens())
 *                 .outputTokens(trace.getOutputTokens())
 *                 .durationMs(trace.getDurationMs())
 *                 .createdAt(trace.getTimestamp())
 *                 .build());
 *     }
 *
 *     // Still required for backward-compat base routing:
 *     @Override
 *     protected void saveAuditLog(String sessionId, String eventType,
 *                                  String input, String output, Integer durationMs) {
 *         // no-op — saveAuditTrace handles persistence
 *     }
 * }
 * }</pre>
 */
@Getter
@Builder
public final class AgentRequestTrace {

    /** The session identifier. */
    private final String sessionId;

    /** The active agent/sub-agent name at the time of the call. */
    private final String agentName;

    /**
     * Event type constant from {@link io.agentcore.advisor.BaseAuditAdvisor}:
     * {@code CALL_START}, {@code CALL_RESULT}, or {@code CALL_ERROR}.
     */
    private final String eventType;

    /** Summary of the user input (system + user messages). May be null on result/error events. */
    private final String userInput;

    /** Summary of the assistant output or error message. May be null on start events. */
    private final String assistantOutput;

    /** Number of input/prompt tokens consumed. Null when not reported by the model. */
    private final Integer inputTokens;

    /** Number of output/completion tokens generated. Null when not reported by the model. */
    private final Integer outputTokens;

    /** Duration of the LLM call in milliseconds. Null on start events. */
    private final Long durationMs;

    /** Timestamp when the event was captured. */
    private final Instant timestamp;

    /**
     * Extensible metadata for domain-specific fields
     * (e.g. market, workflow step, model name).
     */
    @Builder.Default
    private final Map<String, Object> metadata = new HashMap<>();

    public Map<String, Object> getMetadata() {
        return Map.copyOf(metadata);
    }
}
