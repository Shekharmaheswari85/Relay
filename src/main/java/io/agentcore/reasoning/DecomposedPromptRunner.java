/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.reasoning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements the <em>Decomposed Prompting</em> strategy by splitting a complex task into
 * independent parallel sub-tasks, executing all sub-tasks concurrently on Java 21 virtual
 * threads, and synthesising the partial results into a single final answer.
 *
 * <h3>Strategy overview</h3>
 * <p>Decomposed Prompting differs from {@link LeastToMostSolver} in one key way: the
 * sub-tasks are <em>independent</em> of one another and therefore execute in parallel
 * rather than sequentially. The three-phase pipeline is:
 * <ol>
 *   <li><b>Decomposition</b> — a single LLM call lists all independent sub-tasks needed
 *       to answer the original question.</li>
 *   <li><b>Parallel execution</b> — each sub-task is sent to the LLM concurrently on a
 *       virtual thread. Wall-clock latency is bounded by the slowest sub-task, not their
 *       sum.</li>
 *   <li><b>Synthesis</b> — a final LLM call receives the original task together with all
 *       sub-task results and produces a single coherent answer.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DecomposedPromptRunner runner = DecomposedPromptRunner.builder(chatClient)
 *         .maxSubTasks(6)
 *         .timeoutSeconds(45)
 *         .build();
 *
 * String answer = runner.run(
 *     "Analyse our Q1 performance: compare revenue, margin, and customer satisfaction " +
 *     "against Q1 last year and our industry benchmark.");
 * }</pre>
 *
 * <h3>Sub-task parsing</h3>
 * <p>The default implementation splits the decomposition response on newlines, strips
 * leading numbering, and discards blank lines — the same approach as
 * {@link LeastToMostSolver}. Override {@link #parseSubTasks(String)} for custom formats.
 *
 * @see ReasoningStrategy#DECOMPOSED
 * @see LeastToMostSolver
 */
@Slf4j
public class DecomposedPromptRunner {

    private static final int DEFAULT_MAX_SUB_TASKS = 6;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private static final String DEFAULT_DECOMPOSITION_TEMPLATE = """
            Break down the following task into independent sub-tasks that can be answered
            separately in parallel. Each sub-task should cover a distinct aspect of the task.

            Task: {task}

            List the independent sub-tasks, one per line, using the format: 1. <sub-task>:
            """;

    private static final String DEFAULT_SUB_TASK_TEMPLATE = """
            Task context: {task}

            Answer only this specific sub-task:
            {subtask}
            """;

    private static final String DEFAULT_SYNTHESIS_TEMPLATE = """
            Combine the following partial answers into a single, coherent, well-structured response.

            Original task: {task}

            Partial answers:
            {results}

            Synthesise these into a complete answer:
            """;

    private final ChatClient chatClient;
    private final int maxSubTasks;
    private final int timeoutSeconds;
    private final String decompositionTemplate;
    private final String subTaskTemplate;
    private final String synthesisTemplate;

    /**
     * Creates a runner from the given builder state. Use {@link #builder(ChatClient)} to
     * obtain a builder.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected DecomposedPromptRunner(final Builder builder) {
        this.chatClient = Objects.requireNonNull(builder.chatClient, "ChatClient must not be null");
        this.maxSubTasks = Math.max(1, builder.maxSubTasks);
        this.timeoutSeconds = Math.max(1, builder.timeoutSeconds);
        this.decompositionTemplate = builder.decompositionTemplate != null
                ? builder.decompositionTemplate : DEFAULT_DECOMPOSITION_TEMPLATE;
        this.subTaskTemplate = builder.subTaskTemplate != null
                ? builder.subTaskTemplate : DEFAULT_SUB_TASK_TEMPLATE;
        this.synthesisTemplate = builder.synthesisTemplate != null
                ? builder.synthesisTemplate : DEFAULT_SYNTHESIS_TEMPLATE;
    }

    /**
     * Returns a fluent builder for configuring a {@link DecomposedPromptRunner}.
     *
     * @param chatClient the {@link ChatClient} used for all LLM calls; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Runs the Decomposed Prompting pipeline for the given {@code task}.
     *
     * <p>Returns the synthesised final answer, or {@code null} if the decomposition call
     * fails or produces no sub-tasks.
     *
     * @param task the complex task to solve; never {@code null}
     * @return the synthesised final answer, or {@code null} on failure
     */
    @Nullable
    public String run(@NonNull final String task) {
        Objects.requireNonNull(task, "Task must not be null");

        List<String> subTasks = decompose(task);
        if (subTasks.isEmpty()) {
            log.warn("DecomposedPromptRunner: decomposition produced no sub-tasks for task (len={})",
                    task.length());
            return null;
        }

        log.debug("DecomposedPromptRunner: decomposed into {} sub-tasks, executing in parallel", subTasks.size());

        Map<Integer, String> results = executeSubTasksInParallel(task, subTasks);

        if (results.isEmpty()) {
            log.warn("DecomposedPromptRunner: all {} sub-tasks failed or timed out", subTasks.size());
            return null;
        }

        log.debug("DecomposedPromptRunner: {} of {} sub-tasks completed, synthesising",
                results.size(), subTasks.size());

        return synthesise(task, subTasks, results);
    }

    // ─── Protected extension points ───────────────────────────────────────────

    /**
     * Parses the raw decomposition response into an ordered list of independent sub-task
     * descriptions.
     *
     * <p>The default implementation splits on newlines, strips common list prefixes
     * ({@code 1.}, {@code -}, {@code *}, {@code •}), and discards blank lines.
     * Override to handle structured outputs such as JSON arrays.
     *
     * @param decompositionResponse the raw decomposition response; never {@code null}
     * @return an ordered, non-null, possibly empty list of sub-task descriptions
     */
    @NonNull
    protected List<String> parseSubTasks(@NonNull final String decompositionResponse) {
        List<String> parsed = Arrays.stream(decompositionResponse.split("\\n"))
                .map(String::trim)
                .map(line -> line.replaceFirst("^\\d+[.)\\s]+", ""))
                .map(line -> line.replaceFirst("^[-*•]\\s*", ""))
                .filter(line -> !line.isBlank())
                .limit(maxSubTasks)
                .toList();
        return Objects.requireNonNull(parsed, "Parsed sub-tasks must not be null");
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<String> decompose(final String task) {
        try {
            String prompt = decompositionTemplate.replace("{task}", task);
            String response = chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            return parseSubTasks(response);
        } catch (Exception ex) {
            log.warn("DecomposedPromptRunner: decomposition LLM call failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private Map<Integer, String> executeSubTasksInParallel(
            final String task, final List<String> subTasks) {

        ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(subTasks.size());

        for (int i = 0; i < subTasks.size(); i++) {
            final int index = i;
            final String subTask = subTasks.get(i);

            Thread.ofVirtual().start(() -> {
                try {
                    String result = executeSubTask(task, subTask);
                    if (result != null && !result.isBlank()) {
                        results.put(index, result.trim());
                    }
                } catch (Exception ex) {
                    log.debug("DecomposedPromptRunner: sub-task {} failed: {}", index + 1, ex.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("DecomposedPromptRunner: {} of {} sub-tasks completed within timeout",
                        results.size(), subTasks.size());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("DecomposedPromptRunner: interrupted while waiting for sub-tasks");
        }

        return results;
    }

    @Nullable
    private String executeSubTask(final String task, final String subTask) {
        String prompt = subTaskTemplate
                .replace("{task}", task)
                .replace("{subtask}", subTask);
        return chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
    }

    @Nullable
    private String synthesise(
            final String task,
            final List<String> subTasks,
            final Map<Integer, String> results) {
        try {
            String resultsBlock = buildResultsBlock(subTasks, results);
            String prompt = synthesisTemplate
                    .replace("{task}", task)
                    .replace("{results}", resultsBlock);
            return chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
        } catch (Exception ex) {
            log.warn("DecomposedPromptRunner: synthesis LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    private String buildResultsBlock(
            final List<String> subTasks, final Map<Integer, String> results) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < subTasks.size(); i++) {
            String result = results.get(i);
            if (result != null) {
                lines.add((i + 1) + ". [" + subTasks.get(i) + "]\n   " + result);
            }
        }
        return String.join("\n\n", lines);
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link DecomposedPromptRunner} with customised
     * settings. Obtain an instance via {@link DecomposedPromptRunner#builder(ChatClient)}.
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private int maxSubTasks = DEFAULT_MAX_SUB_TASKS;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        @Nullable
        private String decompositionTemplate;
        @Nullable
        private String subTaskTemplate;
        @Nullable
        private String synthesisTemplate;

        private Builder(final ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
        }

        /**
         * Sets the maximum number of independent sub-tasks to decompose into.
         *
         * <p>Sub-tasks beyond this limit are silently dropped. Values less than 1 are
         * clamped to 1.
         *
         * @param maxSubTasks the sub-task cap (default: 6)
         * @return this builder
         */
        public Builder maxSubTasks(final int maxSubTasks) {
            this.maxSubTasks = maxSubTasks;
            return this;
        }

        /**
         * Sets the maximum wall-clock time to wait for all parallel sub-tasks to complete.
         *
         * <p>Sub-tasks that exceed this budget are excluded from the synthesis step.
         * Values less than 1 are clamped to 1 second.
         *
         * @param timeoutSeconds the timeout in seconds (default: 60)
         * @return this builder
         */
        public Builder timeoutSeconds(final int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Overrides the prompt used to decompose the task into independent sub-tasks.
         *
         * <p>Must contain the placeholder {@code {task}}.
         *
         * @param decompositionTemplate the decomposition prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder decompositionTemplate(@Nullable final String decompositionTemplate) {
            this.decompositionTemplate = decompositionTemplate;
            return this;
        }

        /**
         * Overrides the prompt used to execute each individual sub-task.
         *
         * <p>May contain:
         * <ul>
         *   <li>{@code {task}} — the original task statement</li>
         *   <li>{@code {subtask}} — the specific sub-task description</li>
         * </ul>
         *
         * @param subTaskTemplate the sub-task execution prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder subTaskTemplate(@Nullable final String subTaskTemplate) {
            this.subTaskTemplate = subTaskTemplate;
            return this;
        }

        /**
         * Overrides the synthesis prompt that combines all sub-task results.
         *
         * <p>May contain:
         * <ul>
         *   <li>{@code {task}} — the original task statement</li>
         *   <li>{@code {results}} — the numbered list of sub-task results</li>
         * </ul>
         *
         * @param synthesisTemplate the synthesis prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder synthesisTemplate(@Nullable final String synthesisTemplate) {
            this.synthesisTemplate = synthesisTemplate;
            return this;
        }

        /**
         * Constructs the {@link DecomposedPromptRunner} from the current builder state.
         *
         * @return a fully configured runner; never {@code null}
         */
        public DecomposedPromptRunner build() {
            return new DecomposedPromptRunner(this);
        }
    }
}
