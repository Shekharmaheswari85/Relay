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
 * Step 4 of the default programmatic CoT pipeline: Reviews and critiques the generated
 * execution output, verifying mathematical/logical correctness and sanity constraints.
 */
public class VerificationStep implements ReasoningStep {

    private static final String SYSTEM_PROMPT = """
            You are a rigorous quality assurance and verification system.
            Your task is to analyze the draft solution of a problem and verify its logical, mathematical,
            and constraint correctness.
            
            You must explicitly check:
            1. Does the execution solve the original problem completely?
            2. Are all calculations and formula evaluations mathematically correct?
            3. Are all specified constraints and bounds respected?
            4. Is the overall solution reasonable and logical?
            
            Output your verification findings and clearly state whether the solution is VERIFIED or FAILED.
            """;

    @Override
    public String getName() {
        return "Solution Verification";
    }

    @Override
    public String getDescription() {
        return "Reviews and critiques the generated solution to verify logic, math, and constraint correctness.";
    }

    @Override
    public StepResult execute(final String input, final Map<String, Object> context, final ChatClient chatClient) {
        Objects.requireNonNull(input, "Input must not be null");
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(chatClient, "ChatClient must not be null");

        String execution = (String) context.get("solution_execution");
        if (execution == null || execution.isBlank()) {
            return new StepResult(getName(), input, "", "Error: missing 'solution_execution' in execution context", false, Duration.ZERO);
        }

        String originalProblem = (String) context.get("original_input");

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(Objects.requireNonNull(String.format("Problem: %s\n\nExecution Draft:\n%s\n\nVerify this draft:", originalProblem, execution), "User prompt must not be null"))
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return new StepResult(getName(), input, "", "Received empty response from verification model", false, Duration.ZERO);
        }

        context.put("solution_verification", response);
        
        // Quality gate: if LLM explicitly flags "FAILED", fail the step
        boolean isSuccess = !response.toUpperCase().contains("FAILED");

        return new StepResult(getName(), input, response, "Verified solution successfully", isSuccess, Duration.ZERO);
    }
}
