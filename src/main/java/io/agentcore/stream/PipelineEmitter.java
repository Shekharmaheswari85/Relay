/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.stream;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Typed wrapper around Spring MVC's {@link SseEmitter} that provides named event emission
 * helpers for the full agent SSE event vocabulary.
 *
 * <p>{@code PipelineEmitter} is the central I/O handle passed through an agent turn. The
 * orchestrator, sub-agents, tool aspect, and progress publisher all write to it directly —
 * no reactive pipelines, no Sinks, no Flux merging. Events emitted on any virtual thread
 * are serialised by the underlying {@link SseEmitter} and delivered to the HTTP client in
 * arrival order.
 *
 * <h3>Event vocabulary</h3>
 * <table>
 *   <tr><th>Method</th><th>SSE event name</th><th>When to use</th></tr>
 *   <tr><td>{@link #sendThinking}</td><td>{@code thinking}</td><td>Agent reasoning / pre/post LLM metadata</td></tr>
 *   <tr><td>{@link #sendMessage}</td><td>{@code message}</td><td>LLM answer text chunks</td></tr>
 *   <tr><td>{@link #sendToolProgress}</td><td>{@code tool_progress}</td><td>Tool lifecycle events</td></tr>
 *   <tr><td>{@link #sendStage}</td><td>{@code stage}</td><td>Workflow progress milestones</td></tr>
 *   <tr><td>{@link #sendFollowUpQuestions}</td><td>{@code follow_up_questions}</td><td>Suggested follow-ups</td></tr>
 *   <tr><td>{@link #sendConfirmationRequired}</td><td>{@code confirmation_required}</td><td>Mutation confirmation gate</td></tr>
 *   <tr><td>{@link #sendAgentHandoff}</td><td>{@code agent_handoff}</td><td>A2A delegation signal</td></tr>
 *   <tr><td>{@link #sendError}</td><td>{@code error}</td><td>Non-fatal error detail</td></tr>
 *   <tr><td>{@link #sendDone}</td><td>{@code done}</td><td>Terminal event — stream complete</td></tr>
 * </table>
 *
 * <h3>Lifecycle</h3>
 * <p>Call {@link #sendDone()} to signal normal completion. The underlying emitter is
 * completed atomically — subsequent calls to any {@code send*} method are silently dropped.
 * If the HTTP client disconnects mid-stream, {@link IOException} is caught internally and
 * the emitter is marked complete.
 *
 * <h3>Thread safety</h3>
 * <p>All {@code send*} methods are safe to call from any virtual thread concurrently.
 * {@link SseEmitter} serialises concurrent writes internally.
 */
@Slf4j
public class PipelineEmitter {

    private final SseEmitter emitter;
    private final String sessionId;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    /**
     * Creates an emitter wrapping the given {@link SseEmitter}.
     *
     * @param emitter   the underlying Spring MVC SSE emitter; never null
     * @param sessionId the session identifier, used only for logging
     */
    public PipelineEmitter(final SseEmitter emitter, final String sessionId) {
        this.emitter = emitter;
        this.sessionId = sessionId;
    }

    // ─── Typed event senders ──────────────────────────────────────────────────

    /**
     * Emits a {@code thinking} event carrying agent reasoning or LLM metadata JSON.
     *
     * @param json the event payload; should be a JSON object string
     */
    public void sendThinking(final String json) {
        send("thinking", json);
    }

    /**
     * Emits a {@code message} event carrying one text chunk of the LLM answer.
     *
     * @param chunk the text chunk; may be a partial sentence
     */
    public void sendMessage(final String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            send("message", chunk);
        }
    }

    /**
     * Emits a {@code tool_progress} event carrying tool lifecycle JSON.
     *
     * @param json the tool progress payload
     */
    public void sendToolProgress(final String json) {
        send("tool_progress", json);
    }

    /**
     * Emits a {@code stage} milestone event.
     *
     * @param stage    machine-readable stage identifier (e.g., {@code "agent_execution"})
     * @param label    human-readable label (e.g., {@code "Agent executing"})
     * @param progress progress percentage [0, 100]
     */
    public void sendStage(final String stage, final String label, final int progress) {
        String json = String.format(
                "{\"stage\":\"%s\",\"label\":\"%s\",\"progress\":%d}",
                escape(stage), escape(label), progress);
        send("stage", json);
    }

    /**
     * Emits a {@code follow_up_questions} event with suggested follow-up prompts.
     *
     * @param questions the list of follow-up question strings; no-op if empty or null
     */
    public void sendFollowUpQuestions(final List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(questions.get(i))).append("\"");
        }
        sb.append("]");
        send("follow_up_questions", sb.toString());
    }

    /**
     * Emits a {@code confirmation_required} event signalling that the LLM response
     * contains a mutation that needs user approval.
     *
     * @param message the confirmation prompt shown to the user
     */
    public void sendConfirmationRequired(final String message) {
        send("confirmation_required",
                "{\"message\":\"" + escape(message) + "\"}");
    }

    /**
     * Emits an {@code agent_handoff} event signalling A2A delegation to a remote agent.
     *
     * @param toAgent the name of the target agent
     * @param reason  a brief human-readable reason for the handoff
     */
    public void sendAgentHandoff(final String toAgent, final String reason) {
        send("agent_handoff",
                "{\"toAgent\":\"" + escape(toAgent) + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    /**
     * Emits a non-fatal {@code error} event. Does not complete the stream.
     *
     * @param detail a brief description of the error
     */
    public void sendError(final String detail) {
        send("error", "{\"error\":\"" + escape(detail) + "\"}");
    }

    /**
     * Emits a {@code done} terminal event and completes the underlying {@link SseEmitter}.
     * Safe to call multiple times — only the first call has effect.
     */
    public void sendDone() {
        send("done", "{}");
        complete();
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Completes the underlying {@link SseEmitter} without sending a {@code done} event.
     * Prefer {@link #sendDone()} for normal completion. Use this only for error paths.
     */
    public void complete() {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception ex) {
                log.debug("Emitter complete failed for session {}: {}", sessionId, ex.getMessage());
            }
        }
    }

    /**
     * Completes the underlying {@link SseEmitter} with an error.
     *
     * @param t the error that caused the stream to terminate
     */
    public void completeWithError(final Throwable t) {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.completeWithError(Objects.requireNonNull(t, "Throwable must not be null"));
            } catch (Exception ex) {
                log.debug("Emitter completeWithError failed for session {}: {}", sessionId, ex.getMessage());
            }
        }
    }

    /**
     * Returns {@code true} if this emitter has been completed (normally or with error).
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /** Returns the session ID this emitter is associated with. */
    public String getSessionId() {
        return sessionId;
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private void send(final String eventName, final String data) {
        if (completed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(Objects.requireNonNull(eventName, "Event name must not be null")).data(Objects.requireNonNull(data, "Data must not be null")));
        } catch (IOException ex) {
            log.debug("Client disconnected mid-stream: session={} event={}", sessionId, eventName);
            completed.set(true);
        } catch (Exception ex) {
            log.warn("SSE send failed: session={} event={} error={}", sessionId, eventName, ex.getMessage());
        }
    }

    private String escape(final String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
