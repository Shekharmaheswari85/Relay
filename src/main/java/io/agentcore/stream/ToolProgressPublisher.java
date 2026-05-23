/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.stream;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import io.agentcore.session.SessionContextHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * Routes tool-execution lifecycle events to the {@link PipelineEmitter} of the session
 * that triggered the tool call.
 *
 * <p>Each agent turn that opens a streaming response registers its {@link PipelineEmitter}
 * via {@link #register}. Tool aspects and interceptors then call the {@code emit*} methods
 * to push {@code tool_progress} and related SSE events directly to the emitter without
 * requiring access to any reactive pipeline.
 *
 * <p>The session ID is resolved from {@link SessionContextHolder#get()}, which is set on
 * the virtual thread handling the request. No explicit session ID argument is needed for
 * tool-lifecycle events.
 *
 * <h3>Emitting from a tool aspect</h3>
 * <pre>{@code
 * toolProgressPublisher.emitToolStart("myTool", "Starting data analysis", "input=123");
 *
 * // ... execute tool ...
 *
 * toolProgressPublisher.emitToolSuccess("myTool", "Completed analysis", 1500L, "rows=42");
 * }</pre>
 *
 * <h3>Adding audit persistence</h3>
 * <p>Override {@link #persistToolProgressEvent} in a subclass to write events to
 * a database or audit log:
 * <pre>{@code
 * @Component
 * public class AuditingToolProgressPublisher extends ToolProgressPublisher {
 *
 *     @Override
 *     protected void persistToolProgressEvent(
 *             String sessionId, String toolName, String status, String message, Long durationMs) {
 *         auditRepository.save(buildAuditRecord(...));
 *     }
 * }
 * }</pre>
 *
 * <p>This class is thread-safe. The emitter map uses {@link ConcurrentHashMap}.
 */
@Component
@ConditionalOnMissingBean(ToolProgressPublisher.class)
@Slf4j
public class ToolProgressPublisher {

    protected final Map<String, PipelineEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a {@link PipelineEmitter} for the given session. Call once when an agent
     * turn starts and a streaming HTTP response is opened.
     *
     * @param sessionId the unique session identifier; must not be {@code null}
     * @param emitter   the emitter to associate with the session; must not be {@code null}
     */
    public void register(final String sessionId, final PipelineEmitter emitter) {
        emitters.put(sessionId, emitter);
    }

    /**
     * Removes the {@link PipelineEmitter} for the given session. Call once the agent turn
     * ends so the emitter is not retained in memory.
     *
     * @param sessionId the session identifier to deregister; a {@code null} or unknown ID
     *                  is a no-op
     */
    public void unregister(final String sessionId) {
        emitters.remove(sessionId);
    }

    /**
     * Emits a {@code tool_progress} SSE event with status {@code "started"}.
     *
     * <p>The session ID is resolved from {@link SessionContextHolder#get()}; the event is
     * silently dropped when no session is in context or no emitter is registered.
     *
     * @param toolName the canonical tool name
     * @param message  a human-readable description of what the tool is about to do
     */
    public void emitToolStart(final String toolName, final String message) {
        emit("started", toolName, message, null, null, null);
    }

    /**
     * Emits a {@code tool_progress} SSE event with status {@code "started"} and an optional
     * summary of the tool's input arguments.
     *
     * @param toolName     the canonical tool name
     * @param message      a human-readable description of what the tool is about to do
     * @param inputSummary a brief, non-sensitive summary of the tool input; may be {@code null}
     */
    public void emitToolStart(final String toolName, final String message, final String inputSummary) {
        emit("started", toolName, message, null, inputSummary, null);
    }

    /**
     * Emits a {@code tool_progress} SSE event with status {@code "completed"}.
     *
     * @param toolName   the canonical tool name
     * @param message    a human-readable description of what the tool produced
     * @param durationMs wall-clock execution time in milliseconds; may be {@code null}
     */
    public void emitToolSuccess(final String toolName, final String message, final Long durationMs) {
        emit("completed", toolName, message, durationMs, null, null);
    }

    /**
     * Emits a {@code tool_progress} SSE event with status {@code "completed"} and an optional
     * summary of the tool's output.
     *
     * @param toolName      the canonical tool name
     * @param message       a human-readable description of what the tool produced
     * @param durationMs    wall-clock execution time in milliseconds; may be {@code null}
     * @param outputSummary a brief, non-sensitive summary of the tool result; may be {@code null}
     */
    public void emitToolSuccess(
            final String toolName, final String message, final Long durationMs, final String outputSummary) {
        emit("completed", toolName, message, durationMs, null, outputSummary);
    }

    /**
     * Emits a {@code tool_progress} SSE event with status {@code "failed"}.
     *
     * @param toolName   the canonical tool name
     * @param message    a human-readable description of the failure
     * @param durationMs wall-clock execution time in milliseconds before the failure; may be
     *                   {@code null}
     */
    public void emitToolError(final String toolName, final String message, final Long durationMs) {
        emit("failed", toolName, message, durationMs, null, null);
    }

    /**
     * Emits a {@code thinking} SSE event to the given session's emitter.
     *
     * <p>Unlike the tool-lifecycle methods, this method requires an explicit {@code sessionId}
     * because it may be called before {@link SessionContextHolder} has been populated.
     *
     * @param sessionId the session to target; silently ignored when {@code null}, blank, or
     *                  no emitter is registered for the ID
     * @param message   a human-readable description of what the agent is reasoning about
     */
    public void emitThinking(final String sessionId, final String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PipelineEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            String payload = "{\"message\":\"" + escape(message) + "\"}";
            emitter.sendThinking(payload);
        } catch (Exception ex) {
            log.warn("thinking emit dropped for session {}: {}", sessionId, ex.getMessage());
        }
    }

    /**
     * Emits a {@code notice} SSE event to the session inferred from
     * {@link SessionContextHolder#get()}.
     *
     * <p>A notice is an informational, non-critical event that the client may display as a
     * soft indicator (e.g., "cache hit — skipping network call"). It is silently dropped when
     * no session is in context or no emitter is registered.
     *
     * @param toolName the canonical tool name associated with the notice
     * @param message  a human-readable description of the informational condition
     * @param reason   additional context explaining why the notice was raised; may be {@code null}
     */
    public void emitNotice(final String toolName, final String message, final String reason) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PipelineEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            String payload = "{\"tool\":\"" + escape(toolName)
                    + "\",\"message\":\"" + escape(message)
                    + "\",\"reason\":\"" + escape(reason) + "\"}";
            emitter.sendToolProgress(payload);
        } catch (Exception ex) {
            log.warn("notice emit dropped: tool={} error={}", toolName, ex.getMessage());
        }
    }

    /**
     * Emits an {@code agent_handoff} SSE event notifying the client that control has been
     * transferred to another sub-agent.
     *
     * @param sessionId the session to target; silently ignored when {@code null}, blank, or
     *                  no emitter is registered for the ID
     * @param toAgent   the name of the agent receiving control; must not be {@code null}
     * @param reason    a brief human-readable reason for the handoff; may be {@code null}
     */
    public void emitAgentHandoff(final String sessionId, final String toAgent, final String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PipelineEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.sendAgentHandoff(toAgent, reason);
        } catch (Exception ex) {
            log.warn("agent_handoff emit dropped: session={} toAgent={} error={}", sessionId, toAgent, ex.getMessage());
        }
    }

    /**
     * Core dispatch method called by all public {@code emitTool*} variants.
     *
     * <p>Resolves the session ID from {@link SessionContextHolder}, calls
     * {@link #persistToolProgressEvent} for optional persistence, then pushes a
     * {@code tool_progress} SSE event to the session's emitter. The emit is silently dropped
     * when no session is in context or no emitter is registered.
     *
     * @param status        event status: {@code "started"}, {@code "completed"}, or
     *                      {@code "failed"}
     * @param toolName      the canonical tool name
     * @param message       human-readable description
     * @param durationMs    wall-clock duration in milliseconds; {@code null} when not applicable
     * @param inputSummary  brief input description; {@code null} when not provided
     * @param outputSummary brief output description; {@code null} when not provided
     */
    protected void emit(
            final String status,
            final String toolName,
            final String message,
            final Long durationMs,
            final String inputSummary,
            final String outputSummary) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("emit dropped: no session in context, tool={} status={}", toolName, status);
            return;
        }

        persistToolProgressEvent(sessionId, toolName, status, message, durationMs);

        PipelineEmitter emitter = emitters.get(sessionId);
        if (emitter == null) {
            return;
        }

        try {
            String payload = buildPayload(status, toolName, message, durationMs, inputSummary, outputSummary);
            emitter.sendToolProgress(payload);
        } catch (Exception ex) {
            log.warn("tool_progress emit dropped: tool={} status={} error={}", toolName, status, ex.getMessage());
        }
    }

    /**
     * Extension point called by {@link #emit} before each event is pushed to the emitter.
     * The default implementation is a no-op.
     *
     * <p>Override in a subclass annotated with {@code @Component} (combined with the
     * {@code @ConditionalOnMissingBean} guard on this class) to write tool execution records
     * to a database, audit log, or metrics system.
     *
     * @param sessionId  the session ID resolved from context
     * @param toolName   the canonical tool name
     * @param status     event status: {@code "started"}, {@code "completed"}, or {@code "failed"}
     * @param message    human-readable description
     * @param durationMs wall-clock duration in milliseconds; {@code null} when not applicable
     */
    protected void persistToolProgressEvent(
            final String sessionId,
            final String toolName,
            final String status,
            final String message,
            final Long durationMs) {
    }

    /**
     * Assembles the JSON payload string for a {@code tool_progress} SSE event.
     *
     * @param status        event status
     * @param toolName      canonical tool name
     * @param message       human-readable description
     * @param durationMs    optional execution duration
     * @param inputSummary  optional input summary
     * @param outputSummary optional output summary
     * @return a valid JSON string ready for use as SSE event data
     */
    protected String buildPayload(
            final String status,
            final String toolName,
            final String message,
            final Long durationMs,
            final String inputSummary,
            final String outputSummary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("tool", toolName);
        data.put("message", message);
        data.put("createdAt", LocalDateTime.now().toString());
        if (inputSummary != null && !inputSummary.isBlank()) {
            data.put("input", inputSummary);
        }
        if (outputSummary != null && !outputSummary.isBlank()) {
            data.put("output", outputSummary);
        }
        if (durationMs != null) {
            data.put("durationMs", durationMs);
        }
        return toJsonPayload(data);
    }

    private String toJsonPayload(final Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number) {
                sb.append(value);
            } else {
                sb.append("\"").append(escape(String.valueOf(value))).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Minimally escapes {@code value} for safe embedding in a JSON string literal.
     *
     * @param value the string to escape; {@code null} returns an empty string
     * @return the JSON-safe escaped string
     */
    protected String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
