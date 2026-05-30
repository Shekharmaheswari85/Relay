/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Represents a single, isolated step in a linear Chain-of-Thought (CoT) reasoning pipeline.
 *
 * <p>Each step is executed sequentially, receiving the output of the previous step as its
 * direct input, alongside a shared execution context map containing accumulated state.
 */
public interface ReasoningStep {

    /**
     * Returns the unique name of this reasoning step.
     *
     * @return the step name; never null
     */
    String getName();

    /**
     * Returns a human-readable description of what this step accomplishes.
     *
     * @return the step description; never null
     */
    String getDescription();

    /**
     * Executes the step's specific reasoning task.
     *
     * @param input      the raw text input for this step (typically output from the previous step)
     * @param context    the mutable shared context map containing prior step outputs and states
     * @param chatClient the Spring AI {@link ChatClient} configured for this step
     * @return a {@link StepResult} indicating success or failure and containing the output text
     */
    StepResult execute(String input, Map<String, Object> context, ChatClient chatClient);
}
