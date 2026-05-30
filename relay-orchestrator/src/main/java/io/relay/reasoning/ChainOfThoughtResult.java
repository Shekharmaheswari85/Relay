/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

import java.util.List;
import java.util.Map;

/**
 * Represents the final aggregated result of a complete programmatic Chain‑of‑Thought pipeline execution.
 *
 * Holds the success flag, the final output, the execution history, and the final shared context.
 */
public class ChainOfThoughtResult {

    private final boolean success;
    private final String finalOutput;
    private final List<StepResult> executionHistory;
    private final Map<String, Object> context;

    public ChainOfThoughtResult(boolean success, String finalOutput, List<StepResult> executionHistory,
            Map<String, Object> context) {
        this.success = success;
        this.finalOutput = finalOutput;
        this.executionHistory = executionHistory == null ? List.of() : List.copyOf(executionHistory);
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public boolean success() {
        return success;
    }

    public String finalOutput() {
        return finalOutput;
    }

    public List<StepResult> executionHistory() {
        return List.copyOf(executionHistory);
    }

    public Map<String, Object> context() {
        return Map.copyOf(context);
    }

}
