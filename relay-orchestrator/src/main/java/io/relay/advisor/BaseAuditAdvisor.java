/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.advisor;

import java.time.Instant;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;
import org.jspecify.annotations.NonNull;

import io.relay.audit.AgentRequestTrace;

import lombok.extern.slf4j.Slf4j;

/**
 * Base advisor that records all LLM call events to an audit trail.
 * <p>
 * Fires three event types around each call:
 * <ul>
 *   <li>{@code CALL_START} – before the LLM call begins</li>
 *   <li>{@code CALL_RESULT} – after a successful LLM call (includes token counts)</li>
 *   <li>{@code CALL_ERROR} – when the LLM call throws an exception</li>
 * </ul>
 *
 * <h3>Basic usage (string-based)</h3>
 * Override {@link #saveAuditLog} to persist to your audit table:
 * <pre>{@code
 * @Component
 * public class MyAuditAdvisor extends BaseAuditAdvisor {
 *
 *     @Override
 *     protected void saveAuditLog(String sessionId, String eventType,
 *                                  String input, String output, Integer durationMs) {
 *         auditLogRepo.save(MyAuditLogDO.builder()
 *                 .sessionId(sessionId).eventType(eventType)
 *                 .inputJson(input).outputJson(output)
 *                 .durationMs(durationMs)
 *                 .build());
 *     }
 * }
 * }</pre>
 *
 * <h3>Structured usage (recommended)</h3>
 * Override {@link #saveAuditTrace} to receive an {@link AgentRequestTrace} with
 * token counts, timestamp, and extensible metadata. When overriding this method,
 * {@link #saveAuditLog} still needs to be implemented but can be a no-op:
 * <pre>{@code
 * @Component
 * public class MyAuditAdvisor extends BaseAuditAdvisor {
 *
 *     @Override
 *     protected void saveAuditTrace(AgentRequestTrace trace) {
 *         myRepo.save(MyAuditDO.builder()
 *                 .sessionId(trace.getSessionId())
 *                 .eventType(trace.getEventType())
 *                 .inputTokens(trace.getInputTokens())
 *                 .outputTokens(trace.getOutputTokens())
 *                 .durationMs(trace.getDurationMs())
 *                 .build());
 *     }
 *
 *     @Override
 *     protected void saveAuditLog(String sessionId, String eventType,
 *                                  String input, String output, Integer durationMs) {
 *         // delegated to saveAuditTrace — no-op here
 *     }
 * }
 * }</pre>
 *
 * <p>Default order is {@code HIGHEST_PRECEDENCE + 2} (after ConfirmationGateAdvisor).
 */
@Slf4j
public abstract class BaseAuditAdvisor implements CallAdvisor {

    public static final String EVENT_CALL_START = "CALL_START";
    public static final String EVENT_CALL_RESULT = "CALL_RESULT";
    public static final String EVENT_CALL_ERROR = "CALL_ERROR";

    public static final String SESSION_ID_KEY = "session_id";
    public static final String DEFAULT_TOOL_NAME = "chatCall";

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Override
    public @NonNull String getName() {
        return Objects.requireNonNull(getClass().getSimpleName(), "Advisor name must not be null");
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest chatClientRequest,
            final @NonNull CallAdvisorChain callAdvisorChain) {

        String sessionId = extractSessionId(chatClientRequest);
        long startTime = System.currentTimeMillis();
        Instant startInstant = Instant.now();

        fireTrace(AgentRequestTrace.builder()
                .sessionId(sessionId)
                .eventType(EVENT_CALL_START)
                .userInput(summarizeInput(chatClientRequest))
                .timestamp(startInstant)
                .build());

        try {
            ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

            long durationMs = System.currentTimeMillis() - startTime;
            fireTrace(AgentRequestTrace.builder()
                    .sessionId(sessionId)
                    .eventType(EVENT_CALL_RESULT)
                    .assistantOutput(summarizeOutput(response))
                    .inputTokens(extractInputTokens(response))
                    .outputTokens(extractOutputTokens(response))
                    .durationMs(durationMs)
                    .timestamp(Instant.now())
                    .build());

            return response;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            fireTrace(AgentRequestTrace.builder()
                    .sessionId(sessionId)
                    .eventType(EVENT_CALL_ERROR)
                    .assistantOutput(e.getMessage())
                    .durationMs(durationMs)
                    .timestamp(Instant.now())
                    .build());
            throw e;
        }
    }

    // ─── Primary extension points ─────────────────────────────────────────────

    /**
     * Persists a structured audit trace.
     * <p>
     * Default implementation delegates to {@link #saveAuditLog} for backward
     * compatibility. Override to persist the richer {@link AgentRequestTrace}
     * (which includes token counts and timestamp).
     *
     * @param trace the structured audit trace
     */
    protected void saveAuditTrace(final AgentRequestTrace trace) {
        saveAuditLog(
                trace.getSessionId(),
                trace.getEventType(),
                trace.getUserInput(),
                trace.getAssistantOutput(),
                trace.getDurationMs() != null ? trace.getDurationMs().intValue() : null);
    }

    /**
     * Persists a string-based audit log entry.
     * <p>
     * Implementations should handle exceptions gracefully (log and continue).
     * When {@link #saveAuditTrace} is overridden, this method is no longer called
     * by the framework — it can be left as a no-op.
     *
     * @param sessionId  the session identifier
     * @param eventType  the event type ({@code CALL_START}, {@code CALL_RESULT}, {@code CALL_ERROR})
     * @param input      the input summary (may be null)
     * @param output     the output summary (may be null)
     * @param durationMs the call duration in milliseconds (null for CALL_START)
     */
    protected abstract void saveAuditLog(
            String sessionId,
            String eventType,
            String input,
            String output,
            Integer durationMs);

    // ─── Customization hooks ──────────────────────────────────────────────────

    /**
     * Extracts the session ID from the request context.
     * Override to customize session ID extraction.
     */
    protected String extractSessionId(final ChatClientRequest request) {
        Object sessionId = request.context().get(SESSION_ID_KEY);
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    /**
     * Creates a summary of the input for audit logging.
     * Override to customize input summarization.
     */
    protected String summarizeInput(final ChatClientRequest request) {
        return request.toString();
    }

    /**
     * Creates a summary of the output for audit logging.
     * Override to customize output summarization.
     */
    protected String summarizeOutput(final ChatClientResponse response) {
        return response.toString();
    }

    /**
     * Returns the tool name for audit logging.
     * Override to customize tool name extraction.
     */
    protected String getToolName() {
        return DEFAULT_TOOL_NAME;
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private void fireTrace(final AgentRequestTrace trace) {
        try {
            saveAuditTrace(trace);
        } catch (Exception e) {
            log.error("Failed to save audit trace for session {}: {}", trace.getSessionId(), e.getMessage());
        }
    }

    private Integer extractInputTokens(final ChatClientResponse response) {
        if (response == null) {
            return null;
        }
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return null;
        }
        long count = chatResponse.getMetadata().getUsage().getPromptTokens();
        return (int) count;
    }

    private Integer extractOutputTokens(final ChatClientResponse response) {
        if (response == null) {
            return null;
        }
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return null;
        }
        long count = chatResponse.getMetadata().getUsage().getCompletionTokens();
        return (int) count;
    }
}
