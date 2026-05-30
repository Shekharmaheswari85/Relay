/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.reasoning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import io.relay.reasoning.step.FinalAnswerStep;
import io.relay.reasoning.step.ProblemAnalysisStep;
import io.relay.reasoning.step.SolutionExecutionStep;
import io.relay.reasoning.step.SolutionPlanningStep;
import io.relay.reasoning.step.VerificationStep;

public class ChainOfThoughtPipelineTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;

    @BeforeEach
    public void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    @Test
    public void testPipelineRunsSuccessfullyEndToEnd() {
        // Mock intermediate responses for each step
        when(responseSpec.content())
                .thenReturn("1. Facts: X=5\n2. Unknowns: Y\n3. Constraints: none\n4. Type: math") // ProblemAnalysis
                .thenReturn("Plan: 1. Calculate Y = X * 2") // SolutionPlanning
                .thenReturn("Execution: Y = 10") // SolutionExecution
                .thenReturn("VERIFIED: The solution is perfectly correct and meets constraints") // Verification
                .thenReturn("Dear user, the final calculated value of Y is 10."); // FinalAnswer

        ChainOfThoughtPipeline pipeline = new ChainOfThoughtPipeline(chatClient)
                .addStep(new ProblemAnalysisStep())
                .addStep(new SolutionPlanningStep())
                .addStep(new SolutionExecutionStep())
                .addStep(new VerificationStep())
                .addStep(new FinalAnswerStep());

        ChainOfThoughtResult result = pipeline.execute("Solve for Y given X=5 and Y=2X");

        assertThat(result.success()).isTrue();
        assertThat(result.finalOutput()).contains("Dear user, the final calculated value of Y is 10.");
        assertThat(result.executionHistory()).hasSize(5);

        // Verify shared context state
        Map<String, Object> ctx = result.context();
        assertThat(ctx.get("original_input")).isEqualTo("Solve for Y given X=5 and Y=2X");
        assertThat(ctx.get("problem_analysis")).asString().contains("Facts: X=5");
        assertThat(ctx.get("solution_plan")).asString().contains("Plan:");
        assertThat(ctx.get("solution_execution")).asString().contains("Y = 10");
        assertThat(ctx.get("solution_verification")).asString().contains("VERIFIED");
        assertThat(ctx.get("final_answer")).asString().contains("Y is 10");
    }

    @Test
    public void testPipelineEarlyHaltOnVerificationFailure() {
        // Mock intermediate responses, but make Verification fail
        when(responseSpec.content())
                .thenReturn("Analysis facts...") // ProblemAnalysis
                .thenReturn("Plan details...") // SolutionPlanning
                .thenReturn("Execution draft...") // SolutionExecution
                .thenReturn("FAILED: The calculations are mathematically incorrect") // Verification (contains FAILED)
                .thenReturn("Synthesis..."); // FinalAnswer (should not be reached)

        ChainOfThoughtPipeline pipeline = new ChainOfThoughtPipeline(chatClient)
                .addStep(new ProblemAnalysisStep())
                .addStep(new SolutionPlanningStep())
                .addStep(new SolutionExecutionStep())
                .addStep(new VerificationStep())
                .addStep(new FinalAnswerStep());

        ChainOfThoughtResult result = pipeline.execute("Solve problem");

        // The pipeline should fail and halt at step 4 (Verification)
        assertThat(result.success()).isFalse();
        assertThat(result.executionHistory()).hasSize(4); // 4 steps executed (final answer skipped)
        assertThat(result.executionHistory().get(3).success()).isFalse(); // Verification failed

        Map<String, Object> ctx = result.context();
        assertThat(ctx.get("final_answer")).isNull(); // FinalAnswerStep was skipped
    }
}
