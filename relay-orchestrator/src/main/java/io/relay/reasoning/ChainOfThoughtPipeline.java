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

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates a sequence of {@link ReasoningStep}s to solve a complex problem
 * programmatically, carrying shared context between steps.
 *
 * <p>Execution halts early if any step reports failure or encounters an unhandled exception.
 */
@Slf4j
public class ChainOfThoughtPipeline {

    private final ChatClient chatClient;
    private final List<ReasoningStep> steps = new ArrayList<>();
    private final Map<String, Object> context = new HashMap<>();

    /**
     * Creates a new ChainOfThoughtPipeline using the provided Spring AI {@link ChatClient}.
     *
     * @param chatClient the chat client used for AI reasoning; never null
     */
    public ChainOfThoughtPipeline(final ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
    }

    /**
     * Adds a reasoning step to the end of this pipeline.
     *
     * @param step the reasoning step to add; never null
     * @return this pipeline instance for fluent chaining
     */
    public ChainOfThoughtPipeline addStep(final ReasoningStep step) {
        this.steps.add(Objects.requireNonNull(step, "ReasoningStep must not be null"));
        return this;
    }

    /**
     * Executes the Chain-of-Thought pipeline sequentially.
     *
     * @param input the initial user prompt/problem query; never null
     * @return a {@link ChainOfThoughtResult} containing the overall outcome, final output, and audit trace
     */
    public ChainOfThoughtResult execute(final String input) {
        Objects.requireNonNull(input, "Input must not be null");
        String currentInput = input;
        context.put("original_input", input);
        List<StepResult> history = new ArrayList<>();

        log.info("Starting Chain-of-Thought pipeline execution with {} steps", steps.size());

        for (ReasoningStep step : steps) {
            Instant start = Instant.now();
            try {
                log.debug("Executing CoT pipeline step: {}", step.getName());
                StepResult result = step.execute(currentInput, context, chatClient);
                Duration latency = Duration.between(start, Instant.now());

                // Enrich step result with exact measured latency
                StepResult enrichedResult = new StepResult(
                        result.stepName(),
                        result.input(),
                        result.output(),
                        result.logs(),
                        result.success(),
                        latency
                );

                history.add(enrichedResult);

                if (!enrichedResult.success()) {
                    log.warn("Step '{}' failed or signaled termination. Halting pipeline execution.", step.getName());
                    return new ChainOfThoughtResult(false, currentInput, history, context);
                }

                currentInput = enrichedResult.output();
            } catch (Exception ex) {
                Duration latency = Duration.between(start, Instant.now());
                log.error("Unhandled exception in CoT step '{}': {}", step.getName(), ex.getMessage(), ex);
                history.add(new StepResult(
                        step.getName(),
                        currentInput,
                        "",
                        "Error: " + ex.getMessage(),
                        false,
                        latency
                ));
                return new ChainOfThoughtResult(false, currentInput, history, context);
            }
        }

        log.info("Chain-of-Thought pipeline completed successfully");
        return new ChainOfThoughtResult(true, currentInput, history, context);
    }
}
