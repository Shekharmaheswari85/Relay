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
 * Represents the final aggregated result of a complete programmatic Chain-of-Thought pipeline execution.
 *
 * @param success          indicates if all registered steps executed successfully without early failure
 * @param finalOutput      the generated final response text of the pipeline (output of the final step)
 * @param executionHistory the ordered audit trail of every step's execution and latency metrics
 * @param context          the final state of the shared execution context map
 */
public record ChainOfThoughtResult(
        boolean success,
        String finalOutput,
        List<StepResult> executionHistory,
        Map<String, Object> context
) {
}
