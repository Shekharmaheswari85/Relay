/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning.step;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;

import io.relay.reasoning.ReasoningStep;
import io.relay.reasoning.StepResult;

/**
 * Step 3 of the default programmatic CoT pipeline: Executes the generated solution plan
 * step-by-step, showing intermediate calculations or logical reasoning.
 */
public class SolutionExecutionStep implements ReasoningStep {

    private static final String SYSTEM_PROMPT = """
            You are an execution and calculation engine. Your task is to solve the problem by
            rigorously following the provided strategy plan.
            Show all intermediate calculations, reasoning chains, and intermediate results.
            Be mathematically and logically precise.
            """;

    @Override
    public String getName() {
        return "Solution Execution";
    }

    @Override
    public String getDescription() {
        return "Executes the strategy plan step-by-step, producing intermediate details and calculations.";
    }

    @Override
    public StepResult execute(final String input, final Map<String, Object> context, final ChatClient chatClient) {
        Objects.requireNonNull(input, "Input must not be null");
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(chatClient, "ChatClient must not be null");

        String plan = (String) context.get("solution_plan");
        if (plan == null || plan.isBlank()) {
            return new StepResult(getName(), input, "", "Error: missing 'solution_plan' in execution context", false, Duration.ZERO);
        }

        String originalProblem = (String) context.get("original_input");

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(Objects.requireNonNull(String.format("Problem: %s\n\nPlan: %s\n\nExecute the plan step-by-step:", originalProblem, plan), "User prompt must not be null"))
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return new StepResult(getName(), input, "", "Received empty response from execution model", false, Duration.ZERO);
        }

        context.put("solution_execution", response);
        return new StepResult(getName(), input, response, "Executed solution plan successfully", true, Duration.ZERO);
    }
}
