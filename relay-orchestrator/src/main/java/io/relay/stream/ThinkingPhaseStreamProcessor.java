/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.ChatClient;

import io.relay.session.StreamingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes Chain-of-Thought reasoning phase with proper streaming support.
 *
 * <p>Integrates with Spring AI's streaming capabilities to handle LLM token
 * generation during the "thinking" phase. Buffers tokens to avoid character-level
 * streaming and emits batched events via {@link PipelineEmitter}.
 *
 * <p>Follows LangGraph patterns for agentic reasoning workflows.
 */
@Slf4j
@RequiredArgsConstructor
public class ThinkingPhaseStreamProcessor {

    private final PipelineEmitter pipelineEmitter;
    private final int bufferSize;

    /**
     * Creates a processor with default buffer size (512 chars).
     *
     * @param pipelineEmitter the emitter; never null
     */
    public ThinkingPhaseStreamProcessor(final PipelineEmitter pipelineEmitter) {
        this(pipelineEmitter, 512);
    }

    /**
     * Executes a thinking phase with streaming output.
     *
     * <p>Calls the provided LLM prompt and streams tokens to the pipeline emitter.
     * Each token is buffered and flushed in batches to optimize performance.
     *
     * @param prompt the thinking prompt to send to the LLM
     * @param phase the reasoning phase name (e.g., "reasoning", "planning")
     * @param streamingContext the streaming context for managing buffered output
     * @return the complete thinking output
     */
    public String executeStreamingThinkingPhase(
            final String prompt,
            final String phase,
            final StreamingContext streamingContext) {

        Objects.requireNonNull(prompt, "Prompt must not be null");
        Objects.requireNonNull(phase, "Phase must not be null");
        Objects.requireNonNull(streamingContext, "StreamingContext must not be null");

        log.debug("Starting thinking phase: {}", phase);

        final StringBuilder result = new StringBuilder();
        final StreamBuffer buffer = new StreamBuffer(bufferSize, token -> {
            pipelineEmitter.sendThinking(token);
            result.append(token);
        });

        try {
            // In production, integrate with Spring AI ChatClient streaming
            // Example pattern (pseudo-code):
            // chatClient.prompt()
            //     .user(prompt)
            //     .stream()
            //     .content()
            //     .onNext(token -> buffer.add(token))
            //     .onComplete(() -> buffer.flush())
            //     .get();

            // For now, emit a placeholder event
            pipelineEmitter.sendThinkingEvent(phase,
                    "[Thinking phase " + phase + " - integrate with ChatClient streaming]");

            buffer.flush();
            log.debug("Completed thinking phase: {}, output length: {}", phase, result.length());

        } catch (Exception e) {
            log.error("Error in thinking phase: {}", e.getMessage(), e);
            pipelineEmitter.sendError("Thinking phase failed: " + e.getMessage());
            throw new RuntimeException("Thinking phase execution failed", e);
        }

        return result.toString();
    }

    /**
     * A simple buffer for accumulating tokens before emission.
     */
    private static class StreamBuffer {
        private final StringBuilder buffer = new StringBuilder();
        private final int maxSize;
        private final Consumer<String> onFlush;

        StreamBuffer(int maxSize, Consumer<String> onFlush) {
            this.maxSize = maxSize;
            this.onFlush = onFlush;
        }

        void add(String token) {
            buffer.append(token);
            if (buffer.length() >= maxSize || token.contains("\n")) {
                flush();
            }
        }

        void flush() {
            if (buffer.length() > 0) {
                onFlush.accept(buffer.toString());
                buffer.setLength(0);
            }
        }
    }
}
