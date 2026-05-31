/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;

import io.relay.session.StreamingContext;
import io.relay.stream.PipelineEmitter;
import io.relay.stream.ThinkingPhaseStreamProcessor;
import lombok.extern.slf4j.Slf4j;

/**
 * Chain-of-Thought pipeline with streaming support for reasoning phases.
 *
 * <p>Extends the base {@link ChainOfThoughtPipeline} to integrate with streaming
 * infrastructure for real-time display of reasoning chains. Emits "thinking" events
 * during reasoning phases and "message" events during output generation.
 *
 * <p>Handles buffering of tokens to prevent character-level streaming, following
 * LangGraph patterns for production-grade agentic AI systems.
 */
@Slf4j
public class ChainOfThoughtPipelineStreaming {

    private final ChatClient chatClient;
    private final List<ReasoningStep> steps = new ArrayList<>();
    private final Map<String, Object> context = new HashMap<>();
    private final PipelineEmitter pipelineEmitter;
    private final StreamingContext streamingContext;
    private final ThinkingPhaseStreamProcessor thinkingProcessor;

    /**
     * Creates a streaming CoT pipeline.
     *
     * @param chatClient the Spring AI chat client
     * @param pipelineEmitter the SSE emitter for streaming events
     * @param streamingContext the streaming session context
     */
    public ChainOfThoughtPipelineStreaming(
            final ChatClient chatClient,
            final PipelineEmitter pipelineEmitter,
            final StreamingContext streamingContext) {
        this.chatClient = Objects.requireNonNull(chatClient);
        this.pipelineEmitter = Objects.requireNonNull(pipelineEmitter);
        this.streamingContext = Objects.requireNonNull(streamingContext);
        this.thinkingProcessor = new ThinkingPhaseStreamProcessor(pipelineEmitter);
    }

    /**
     * Adds a reasoning step to the pipeline.
     *
     * @param step the step to add
     * @return this pipeline for chaining
     */
    public ChainOfThoughtPipelineStreaming addStep(final ReasoningStep step) {
        this.steps.add(Objects.requireNonNull(step));
        return this;
    }

    /**
     * Executes the streaming CoT pipeline.
     *
     * <p>Emits stage events as the pipeline progresses through each step.
     * Reasoning phases emit buffered "thinking" events, while output phases
     * emit "message" events.
     *
     * @param input the initial problem/prompt
     * @return the execution result
     */
    public ChainOfThoughtResult executeStreaming(final String input) {
        Objects.requireNonNull(input, "Input must not be null");
        String currentInput = input;
        context.put("original_input", input);
        List<StepResult> history = new ArrayList<>();

        log.info("[{}] Starting streaming CoT pipeline with {} steps",
                streamingContext.getSessionId(), steps.size());

        pipelineEmitter.sendStageEvent("agent_execution", "Executing reasoning pipeline", 0);

        for (int i = 0; i < steps.size(); i++) {
            ReasoningStep step = steps.get(i);
            int progressPercent = (i * 100) / steps.size();

            pipelineEmitter.sendStageEvent("agent_execution",
                    "Executing step: " + step.getName(), progressPercent);

            Instant start = Instant.now();
            try {
                log.debug("[{}] Executing step: {}", streamingContext.getSessionId(), step.getName());

                // Execute step with thinking phase streaming if available
                StepResult result = executeStepWithStreaming(step, currentInput);
                Duration latency = Duration.between(start, Instant.now());

                StepResult enrichedResult = new StepResult(
                        result.stepName(),
                        result.input(),
                        result.output(),
                        result.logs(),
                        result.success(),
                        latency
                );

                history.add(enrichedResult);

                // Stream the output
                if (enrichedResult.output() != null && !enrichedResult.output().isEmpty()) {
                    pipelineEmitter.sendMessage(enrichedResult.output());
                }

                if (!enrichedResult.success()) {
                    log.warn("[{}] Step failed: {}", streamingContext.getSessionId(), step.getName());
                    pipelineEmitter.sendError("Step execution failed: " + step.getName());
                    return new ChainOfThoughtResult(false, currentInput, history, context);
                }

                currentInput = enrichedResult.output();

            } catch (Exception ex) {
                Duration latency = Duration.between(start, Instant.now());
                log.error("[{}] Error in step {}: {}",
                        streamingContext.getSessionId(), step.getName(), ex.getMessage(), ex);
                history.add(new StepResult(
                        step.getName(),
                        currentInput,
                        "",
                        "Error: " + ex.getMessage(),
                        false,
                        latency
                ));
                pipelineEmitter.sendError("Step execution failed: " + ex.getMessage());
                return new ChainOfThoughtResult(false, currentInput, history, context);
            }
        }

        log.info("[{}] Streaming CoT pipeline completed successfully",
                streamingContext.getSessionId());
        pipelineEmitter.sendStageEvent("completed", "Reasoning pipeline completed", 100);
        pipelineEmitter.complete();

        return new ChainOfThoughtResult(true, currentInput, history, context);
    }

    private StepResult executeStepWithStreaming(final ReasoningStep step, final String input) {
        // Execute with thinking phase if step supports streaming
        if (step instanceof StreamingReasoningStep) {
            StreamingReasoningStep streamingStep = (StreamingReasoningStep) step;
            return streamingStep.executeStreaming(input, context, chatClient,
                    thinkingProcessor, streamingContext);
        } else {
            // Fallback to non-streaming execution
            return step.execute(input, context, chatClient);
        }
    }

    /**
     * Interface for steps that support streaming.
     */
    public interface StreamingReasoningStep extends ReasoningStep {
        StepResult executeStreaming(
                String input,
                Map<String, Object> context,
                ChatClient chatClient,
                ThinkingPhaseStreamProcessor thinkingProcessor,
                StreamingContext streamingContext);
    }
}
