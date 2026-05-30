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
 * Step 5 of the default programmatic CoT pipeline: Synthesizes the verified
 * solution details
 * and analysis into a polished, final answer formatted directly for the
 * end-user.
 */
public class FinalAnswerStep implements ReasoningStep {

    private static final String SYSTEM_PROMPT = """
            You are a helpful and professional customer assistant. Your task is to construct the
            final user response to a problem using a verified analysis and solution draft.

            Synthesize all prior details into a polished, readable, and perfectly formatted answer.
            Do not show raw debugging or critique information; present a direct, comprehensive,
            and professional solution.
            """;

    @Override
    public String getName() {
        return "Final Answer Formulation";
    }

    @Override
    public String getDescription() {
        return "Synthesizes the verified solution and analytical facts into a polished final customer response.";
    }

    @Override
    public StepResult execute(final String input, final Map<String, Object> context, final ChatClient chatClient) {
        Objects.requireNonNull(input, "Input must not be null");
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(chatClient, "ChatClient must not be null");

        String originalProblem = (String) context.get("original_input");
        String execution = (String) context.get("solution_execution");
        String verification = (String) context.get("solution_verification");

        if (execution == null || execution.isBlank()) {
            return new StepResult(getName(), input, "", "Error: missing 'solution_execution' in execution context",
                    false, Duration.ZERO);
        }

        String userQuery = String.format("%nProblem: %s%n%nSolution execution:%n%s%n%nVerification report:%n%s%n%nFormulate the final response:%n", originalProblem, execution, verification != null ? verification : "N/A");

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(Objects.requireNonNull(userQuery, "User query must not be null"))
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return new StepResult(getName(), input, "", "Received empty response from final synthesis model", false,
                    Duration.ZERO);
        }

        context.put("final_answer", response);
        return new StepResult(getName(), input, response, "Formulated final answer successfully", true, Duration.ZERO);
    }
}
