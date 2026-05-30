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
 * Step 1 of the default programmatic CoT pipeline: Decomposes a complex problem statement
 * into key facts, unknowns, and constraints.
 */
public class ProblemAnalysisStep implements ReasoningStep {

    private static final String SYSTEM_PROMPT = """
            You are a rigorous analytical system. Your task is to analyze the input problem statement.
            Decompose the problem step by step into the following four categories:
            1. Key facts (explicit data given in the problem statement)
            2. Unknowns (what needs to be solved or derived)
            3. Constraints (specific rules, limitations, or parameters)
            4. Problem type (classification, e.g. mathematical, logical, RAG, tool calling)
            
            Be extremely precise and thorough. Do not attempt to solve or answer the problem yet.
            """;

    @Override
    public String getName() {
        return "Problem Analysis";
    }

    @Override
    public String getDescription() {
        return "Decomposes the problem statement into key facts, unknowns, constraints, and problem type.";
    }

    @Override
    public StepResult execute(final String input, final Map<String, Object> context, final ChatClient chatClient) {
        Objects.requireNonNull(input, "Input must not be null");
        Objects.requireNonNull(context, "Context must not be null");
        Objects.requireNonNull(chatClient, "ChatClient must not be null");

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Analyze the following problem:\n" + input)
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return new StepResult(getName(), input, "", "Received empty response from analysis model", false, Duration.ZERO);
        }

        context.put("problem_analysis", response);
        return new StepResult(getName(), input, response, "Decomposed problem successfully", true, Duration.ZERO);
    }
}
