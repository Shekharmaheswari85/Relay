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
 * Step 2 of the default programmatic CoT pipeline: Generates an actionable step-by-step
 * plan to solve the problem using the decomposition generated in Step 1.
 */
public class SolutionPlanningStep implements ReasoningStep {

    private static final String SYSTEM_PROMPT = """
            You are a strategist and planner. Your task is to generate a logical, robust step-by-step
            solution plan based on a provided problem analysis.
            Do not execute calculations or solve the problem yet; focus purely on the structural
            strategy, steps, and methodology needed to achieve a verifiable final answer.
            """;

    @Override
    public String getName() {
        return "Solution Planning";
    }

    @Override
    public String getDescription() {
        return "Generates a structured plan to solve the problem based on the initial decomposition.";
    }

    @Override
    public StepResult execute(final String input, final Map<String, Object> context, final ChatClient chatClient) {
        Objects.requireNonNull(input, "Input must not be null");
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(chatClient, "ChatClient must not be null");

        String analysis = (String) context.get("problem_analysis");
        if (analysis == null || analysis.isBlank()) {
            return new StepResult(getName(), input, "", "Error: missing 'problem_analysis' in execution context", false, Duration.ZERO);
        }

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Create a plan based on this analysis:\n\n" + analysis)
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return new StepResult(getName(), input, "", "Received empty response from planning model", false, Duration.ZERO);
        }

        context.put("solution_plan", response);
        return new StepResult(getName(), input, response, "Created solution plan successfully", true, Duration.ZERO);
    }
}
