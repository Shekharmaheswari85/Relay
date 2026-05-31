/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages Server-Sent Events (SSE) emission for streaming AI pipeline outputs.
 *
 * <p>Provides buffering mechanisms to batch token chunks before emission, preventing
 * character-level streaming that degrades client-side performance. Handles multiple
 * event types (thinking, message, stage, done) with appropriate serialization.
 *
 * <p>Thread-safe for concurrent access. Designed to work with LangGraph-style event
 * streaming patterns for agentic AI workflows.
 */
@Slf4j
public class PipelineEmitter {

    private static final int THINKING_BUFFER_SIZE = 512; // characters
    private static final int MESSAGE_BUFFER_SIZE = 256; // characters
    private static final long FLUSH_TIMEOUT_MS = 100; // milliseconds

    private final SseEmitter sseEmitter;
    private final String sessionId;
    private final StreamingEventBatcher eventBatcher;

    // Buffers for different event types
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private final StringBuilder messageBuffer = new StringBuilder();
    private volatile long lastFlushTime = System.currentTimeMillis();
    private volatile boolean isEmitterAlive = true;

    /**
     * Creates a pipeline emitter for the given SSE emitter and session.
     *
     * @param sseEmitter the SSE emitter; never null
     * @param sessionId the session identifier; never null
     */
    public PipelineEmitter(final SseEmitter sseEmitter, final String sessionId) {
        this.sseEmitter = Objects.requireNonNull(sseEmitter, "SseEmitter must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "SessionId must not be null");
        this.eventBatcher = new StreamingEventBatcher(this);

        // Configure emitter timeouts
        sseEmitter.setTimeout(300000); // 5 minutes
        sseEmitter.onCompletion(() -> {
            isEmitterAlive = false;
            log.debug("[{}] SSE emitter completed", sessionId);
        });
        sseEmitter.onTimeout(() -> {
            isEmitterAlive = false;
            log.warn("[{}] SSE emitter timed out", sessionId);
        });
    }

    /**
     * Emits a thinking phase event chunk with automatic buffering.
     *
     * <p>Accumulates tokens in a buffer and flushes when the buffer reaches
     * {@link #THINKING_BUFFER_SIZE}, encounters a newline, or timeout occurs.
     * This prevents character-level streaming and improves performance.
     *
     * @param chunk the token chunk to emit; may be null
     */
    public void sendThinking(final String chunk) {
        if (!isEmitterAlive || chunk == null) {
            return;
        }
        synchronized (thinkingBuffer) {
            thinkingBuffer.append(chunk);
            if (shouldFlushThinking()) {
                flushThinkingBuffer();
            }
        }
    }

    /**
     * Emits a message event chunk with automatic buffering.
     *
     * @param chunk the message chunk to emit; may be null
     */
    public void sendMessage(final String chunk) {
        if (!isEmitterAlive || chunk == null) {
            return;
        }
        synchronized (messageBuffer) {
            messageBuffer.append(chunk);
            if (shouldFlushMessage()) {
                flushMessageBuffer();
            }
        }
    }

    /**
     * Emits a thinking phase event with metadata (e.g., phase, content type).
     *
     * @param phase the reasoning phase name (e.g., "reasoning", "planning")
     * @param content the thinking content
     */
    public void sendThinkingEvent(final String phase, final String content) {
        if (!isEmitterAlive) {
            return;
        }
        try {
            Map<String, Object> data = Map.of(
                    "phase", phase,
                    "content", content
            );
            internalSendEvent("thinking", data);
        } catch (IOException e) {
            log.error("[{}] Error sending thinking event: {}", sessionId, e.getMessage());
            isEmitterAlive = false;
        }
    }

    /**
     * Emits a message (agent output) event.
     *
     * @param message the message content
     */
    public void sendMessageEvent(final String message) {
        if (!isEmitterAlive) {
            return;
        }
        try {
            Map<String, Object> data = Map.of("content", message);
            internalSendEvent("message", data);
        } catch (IOException e) {
            log.error("[{}] Error sending message event: {}", sessionId, e.getMessage());
            isEmitterAlive = false;
        }
    }

    /**
     * Emits a stage event (pipeline progress tracking).
     *
     * @param stage the stage name
     * @param label human-readable label
     * @param progress progress percentage (0-100)
     */
    public void sendStageEvent(final String stage, final String label, final int progress) {
        if (!isEmitterAlive) {
            return;
        }
        try {
            Map<String, Object> data = Map.of(
                    "stage", stage,
                    "label", label,
                    "progress", progress
            );
            internalSendEvent("stage", data);
        } catch (IOException e) {
            log.error("[{}] Error sending stage event: {}", sessionId, e.getMessage());
            isEmitterAlive = false;
        }
    }

    /**
     * Flushes all pending buffers and emits a completion event.
     */
    public void complete() {
        if (!isEmitterAlive) {
            return;
        }
        synchronized (thinkingBuffer) {
            flushThinkingBuffer();
        }
        synchronized (messageBuffer) {
            flushMessageBuffer();
        }
        try {
            internalSendEvent("done", Map.of());
            isEmitterAlive = false;
        } catch (IOException e) {
            log.error("[{}] Error sending completion event: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Sends an error event and closes the emitter.
     *
     * @param errorMessage the error message
     */
    public void sendError(final String errorMessage) {
        if (!isEmitterAlive) {
            return;
        }
        try {
            Map<String, Object> data = Map.of("error", errorMessage);
            internalSendEvent("error", data);
        } catch (IOException e) {
            log.error("[{}] Error sending error event: {}", sessionId, e.getMessage());
        } finally {
            isEmitterAlive = false;
        }
    }

    /**
     * Returns whether this emitter is still alive and can accept events.
     *
     * @return true if the emitter is active; false if closed or timed out
     */
    public boolean isAlive() {
        return isEmitterAlive;
    }

    /**
     * Gets the event batcher for batch event operations.
     *
     * @return the streaming event batcher
     */
    public StreamingEventBatcher getEventBatcher() {
        return eventBatcher;
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private boolean shouldFlushThinking() {
        long timeSinceLastFlush = System.currentTimeMillis() - lastFlushTime;
        return thinkingBuffer.length() >= THINKING_BUFFER_SIZE
                || thinkingBuffer.toString().contains("\n")
                || timeSinceLastFlush > FLUSH_TIMEOUT_MS;
    }

    private boolean shouldFlushMessage() {
        long timeSinceLastFlush = System.currentTimeMillis() - lastFlushTime;
        return messageBuffer.length() >= MESSAGE_BUFFER_SIZE
                || timeSinceLastFlush > FLUSH_TIMEOUT_MS;
    }

    private void flushThinkingBuffer() {
        if (thinkingBuffer.length() > 0) {
            try {
                String content = thinkingBuffer.toString();
                thinkingBuffer.setLength(0);
                sendThinkingEvent("reasoning", content);
                lastFlushTime = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("[{}] Error flushing thinking buffer: {}", sessionId, e.getMessage());
            }
        }
    }

    private void flushMessageBuffer() {
        if (messageBuffer.length() > 0) {
            try {
                String content = messageBuffer.toString();
                messageBuffer.setLength(0);
                sendMessageEvent(content);
                lastFlushTime = System.currentTimeMillis();
            } catch (Exception e) {
                log.error("[{}] Error flushing message buffer: {}", sessionId, e.getMessage());
            }
        }
    }

    private void internalSendEvent(final String eventName, final Map<String, Object> data) throws IOException {
        if (!isEmitterAlive) {
            throw new IOException("Emitter is not alive");
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(sessionId)
                    .name(eventName);

            // Serialize data as JSON
            String jsonData = convertToJson(data);
            event.data(jsonData);

            sseEmitter.send(event.build());
            log.debug("[{}] Sent SSE event: {}", sessionId, eventName);
        } catch (IOException e) {
            isEmitterAlive = false;
            throw e;
        }
    }

    private String convertToJson(final Map<String, Object> data) {
        // Simple JSON conversion - in production use Jackson/Gson
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(escapeJson((String) value)).append("\"");
            } else {
                json.append(value);
            }
            first = false;
        }
        json.append("}");
        return json.toString();
    }

    private String escapeJson(final String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
