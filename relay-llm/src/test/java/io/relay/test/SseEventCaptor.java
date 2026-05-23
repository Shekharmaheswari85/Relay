/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.relay.stream.PipelineEmitter;

/**
 * Test utility for capturing and asserting SSE events emitted by agent pipelines.
 *
 * <p>Extends {@link PipelineEmitter} and overrides every {@code send*} method to record
 * events to an in-memory list rather than writing them to a real {@link SseEmitter}.
 * Pass an instance of this class wherever a {@link PipelineEmitter} is expected in unit
 * or integration tests — no HTTP server or SSE connection is required.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SseEventCaptor captor = SseEventCaptor.create("test-session");
 *
 * // Run the agent pipeline synchronously on the current thread:
 * runtimeService.sendMessage("test-session", "Hello?", captor);
 *
 * // Assert outcomes:
 * assertThat(captor.hasDoneEvent()).isTrue();
 * assertThat(captor.getMessageText()).contains("expected output");
 * assertThat(captor.getEventsByType("stage")).hasSizeGreaterThan(0);
 * assertThat(captor.hasEventOfType("follow_up_questions")).isTrue();
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * <p>Events are stored in a {@link CopyOnWriteArrayList} and safe to read from any thread
 * after the agent pipeline completes.
 *
 * @see PipelineEmitter
 */
public class SseEventCaptor extends PipelineEmitter {

    /**
     * Represents a single captured SSE event with its type and raw data payload.
     *
     * @param eventType the SSE event name (e.g., "message", "stage", "done")
     * @param data      the raw event data payload
     */
    public record SseEvent(String eventType, String data) {}

    private final List<SseEvent> events = new CopyOnWriteArrayList<>();
    private volatile boolean doneReceived = false;

    /**
     * Creates a new event captor for the given session.
     *
     * @param sessionId the session identifier (used for logging only)
     */
    private SseEventCaptor(final String sessionId) {
        super(new SseEmitter(0L), sessionId);  // dummy SseEmitter — never actually used
    }

    /**
     * Factory method for fluent construction.
     *
     * @param sessionId the session identifier
     * @return a new {@code SseEventCaptor}
     */
    public static SseEventCaptor create(final String sessionId) {
        return new SseEventCaptor(sessionId);
    }

    // ─── Overridden send methods — record instead of emitting ─────────────────

    @Override
    public void sendThinking(final String json) {
        events.add(new SseEvent("thinking", json));
    }

    @Override
    public void sendMessage(final String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            events.add(new SseEvent("message", chunk));
        }
    }

    @Override
    public void sendToolProgress(final String json) {
        events.add(new SseEvent("tool_progress", json));
    }

    @Override
    public void sendStage(final String stage, final String label, final int progress) {
        String json = String.format("{\"stage\":\"%s\",\"label\":\"%s\",\"progress\":%d}",
                stage, label, progress);
        events.add(new SseEvent("stage", json));
    }

    @Override
    public void sendFollowUpQuestions(final List<String> questions) {
        if (questions == null || questions.isEmpty()) return;
        String data = questions.stream()
                .map(q -> "\"" + q.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        events.add(new SseEvent("follow_up_questions", data));
    }

    @Override
    public void sendConfirmationRequired(final String message) {
        events.add(new SseEvent("confirmation_required",
                "{\"message\":\"" + message + "\"}"));
    }

    @Override
    public void sendAgentHandoff(final String toAgent, final String reason) {
        events.add(new SseEvent("agent_handoff",
                "{\"toAgent\":\"" + toAgent + "\",\"reason\":\"" + reason + "\"}"));
    }

    @Override
    public void sendError(final String detail) {
        events.add(new SseEvent("error", "{\"error\":\"" + detail + "\"}"));
    }

    @Override
    public void sendDone() {
        events.add(new SseEvent("done", "{}"));
        doneReceived = true;
        // Do NOT call super — the dummy SseEmitter doesn't need completing
    }

    @Override
    public void complete() {
        // no-op for test captures
    }

    @Override
    public boolean isCompleted() {
        return doneReceived;
    }

    // ─── Event accessors ──────────────────────────────────────────────────────

    /** Returns all captured events in emission order. */
    public List<SseEvent> getEvents() {
        return List.copyOf(events);
    }

    /**
     * Returns all events of the given type.
     *
     * @param eventType the SSE event name (e.g., "message", "stage", "thinking")
     */
    public List<SseEvent> getEventsByType(final String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.eventType()))
                .toList();
    }

    /**
     * Returns the first event of the given type, if any.
     *
     * @param eventType the SSE event name to look for
     */
    public Optional<SseEvent> getFirstEventOfType(final String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.eventType()))
                .findFirst();
    }

    /**
     * Concatenates all {@code message} event payloads into a single string.
     */
    public String getMessageText() {
        return events.stream()
                .filter(e -> "message".equals(e.eventType()))
                .map(SseEvent::data)
                .collect(Collectors.joining());
    }

    /**
     * Returns the data of the first event matching the given type.
     *
     * @param eventType the SSE event name to look for
     */
    public Optional<String> getDataForType(final String eventType) {
        return getFirstEventOfType(eventType).map(SseEvent::data);
    }

    // ─── Assertion helpers ─────────────────────────────────────────────────────

    /** Returns {@code true} if a {@code done} event was captured. */
    public boolean hasDoneEvent() {
        return doneReceived;
    }

    /** Returns {@code true} if a {@code follow_up_questions} event was captured. */
    public boolean hasFollowUpQuestions() {
        return hasEventOfType("follow_up_questions");
    }

    /** Returns {@code true} if at least one event of the given type was captured. */
    public boolean hasEventOfType(final String eventType) {
        return events.stream().anyMatch(e -> eventType.equals(e.eventType()));
    }

    /** Returns the total number of captured events. */
    public int size() {
        return events.size();
    }

    /** Returns {@code true} if no events were captured. */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Returns a human-readable summary of all events for test debugging.
     */
    public String summarize() {
        List<String> lines = new ArrayList<>();
        lines.add("SseEventCaptor — " + events.size() + " events:");
        for (int i = 0; i < events.size(); i++) {
            SseEvent e = events.get(i);
            String data = e.data() != null && e.data().length() > 80
                    ? e.data().substring(0, 80) + "..."
                    : e.data();
            lines.add(String.format("  [%d] type=%-30s data=%s", i, e.eventType(), data));
        }
        return String.join("\n", lines);
    }
}
