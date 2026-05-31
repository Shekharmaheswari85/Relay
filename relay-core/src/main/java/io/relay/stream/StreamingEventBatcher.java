/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * Batches multiple streaming events before emission to optimize performance.
 *
 * <p>Useful for aggregating multiple small events (e.g., tokens) into larger
 * batches, reducing SSE overhead and network traffic. Particularly effective
 * for LLM token streaming where thousands of tokens may be generated.
 *
 * <p>Thread-safe and designed to work seamlessly with {@link PipelineEmitter}.
 */
@Slf4j
public class StreamingEventBatcher {

    private static final int DEFAULT_BATCH_SIZE = 20;
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 50;

    private final PipelineEmitter emitter;
    private final int maxBatchSize;
    private final long flushIntervalMs;
    private final List<StreamingEvent> eventQueue = new ArrayList<>();
    private volatile long lastFlushTime = System.currentTimeMillis();

    /**
     * Creates a batcher with default settings.
     *
     * @param emitter the target emitter; never null
     */
    public StreamingEventBatcher(final PipelineEmitter emitter) {
        this(emitter, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    /**
     * Creates a batcher with custom settings.
     *
     * @param emitter the target emitter; never null
     * @param maxBatchSize maximum events per batch
     * @param flushIntervalMs milliseconds between flushes
     */
    public StreamingEventBatcher(final PipelineEmitter emitter, final int maxBatchSize,
                                  final long flushIntervalMs) {
        this.emitter = Objects.requireNonNull(emitter, "Emitter must not be null");
        this.maxBatchSize = maxBatchSize;
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * Adds a thinking event to the batch.
     *
     * @param phase the reasoning phase
     * @param content the thinking content
     */
    public void addThinkingEvent(final String phase, final String content) {
        addEvent(new StreamingEvent("thinking",
                Map.of("phase", phase, "content", content)));
    }

    /**
     * Adds a message event to the batch.
     *
     * @param content the message content
     */
    public void addMessageEvent(final String content) {
        addEvent(new StreamingEvent("message", Map.of("content", content)));
    }

    /**
     * Adds a stage event to the batch.
     *
     * @param stage the stage name
     * @param label the label
     * @param progress the progress percentage
     */
    public void addStageEvent(final String stage, final String label, final int progress) {
        addEvent(new StreamingEvent("stage",
                Map.of("stage", stage, "label", label, "progress", progress)));
    }

    /**
     * Adds a generic event to the batch.
     *
     * @param event the event to add
     */
    public void addEvent(final StreamingEvent event) {
        synchronized (eventQueue) {
            eventQueue.add(Objects.requireNonNull(event, "Event must not be null"));
            if (shouldFlush()) {
                flush();
            }
        }
    }

    /**
     * Flushes all pending events immediately.
     */
    public void flush() {
        synchronized (eventQueue) {
            if (eventQueue.isEmpty()) {
                return;
            }
            for (StreamingEvent event : eventQueue) {
                emitEvent(event);
            }
            eventQueue.clear();
            lastFlushTime = System.currentTimeMillis();
        }
    }

    /**
     * Returns the current batch size.
     *
     * @return number of pending events
     */
    public int pendingEventCount() {
        synchronized (eventQueue) {
            return eventQueue.size();
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private boolean shouldFlush() {
        long timeSinceFlush = System.currentTimeMillis() - lastFlushTime;
        return eventQueue.size() >= maxBatchSize || timeSinceFlush > flushIntervalMs;
    }

    private void emitEvent(final StreamingEvent event) {
        try {
            switch (event.type()) {
                case "thinking" -> {
                    Map<String, Object> data = event.data();
                    emitter.sendThinkingEvent((String) data.get("phase"),
                            (String) data.get("content"));
                }
                case "message" -> {
                    Map<String, Object> data = event.data();
                    emitter.sendMessageEvent((String) data.get("content"));
                }
                case "stage" -> {
                    Map<String, Object> data = event.data();
                    emitter.sendStageEvent((String) data.get("stage"),
                            (String) data.get("label"),
                            (Integer) data.get("progress"));
                }
                default -> log.warn("Unknown event type: {}", event.type());
            }
        } catch (Exception e) {
            log.error("Error emitting batched event: {}", e.getMessage());
        }
    }

    /**
     * Represents a single streaming event.
     */
    public record StreamingEvent(String type, Map<String, Object> data) {
    }
}
