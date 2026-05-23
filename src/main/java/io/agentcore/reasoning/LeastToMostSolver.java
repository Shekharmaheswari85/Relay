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
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements the <em>Least-to-Most</em> prompting strategy by decomposing a complex
 * problem into ordered sub-problems and solving them sequentially, carrying established
 * answers forward into each successive step.
 *
 * <h3>Strategy overview</h3>
 * <p>Least-to-Most prompting addresses tasks that are difficult to solve in a single step
 * but become tractable when broken into a progression from simpler to more complex parts.
 * The two-phase process is:
 * <ol>
 *   <li><b>Decomposition</b> — a single LLM call asks the model to list all sub-problems
 *       that must be resolved in order to solve the original task, ordered from easiest
 *       to hardest.</li>
 *   <li><b>Sequential solving</b> — each sub-problem is solved in order. The answer from
 *       each step is appended to a growing "established context" that is passed into the
 *       next sub-problem's prompt, so later steps can build on earlier results.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * LeastToMostSolver solver = LeastToMostSolver.builder(chatClient)
 *         .maxSubProblems(6)
 *         .build();
 *
 * String answer = solver.solve(
 *     "Calculate the average order value for Q1, adjusting for the 12% tax rate " +
 *     "and excluding orders below $50.");
 * }</pre>
 *
 * <h3>Sub-problem parsing</h3>
 * <p>The default implementation splits the decomposition response on newlines, strips
 * leading numbering ({@code 1. }, {@code - }, etc.), and discards blank lines.
 * Override {@link #parseSubProblems(String)} for custom formats (e.g. JSON arrays).
 *
 * @see ReasoningStrategy#LEAST_TO_MOST
 */
@Slf4j
public class LeastToMostSolver {

    private static final int DEFAULT_MAX_SUB_PROBLEMS = 8;

    private static final String DEFAULT_DECOMPOSITION_TEMPLATE = """
            Break down the following problem into an ordered list of sub-problems, from simplest
            to most complex. Each sub-problem must be solvable independently and should build
            toward the final answer.

            Problem: {problem}

            List the sub-problems, one per line, using the format: 1. <sub-problem>:
            """;

    private static final String DEFAULT_SOLVING_TEMPLATE = """
            Problem: {problem}

            Sub-problem to solve now: {subproblem}

            {context}Solve only this sub-problem, using the previously established facts:
            """;

    private static final String CONTEXT_PREFIX = "Previously established:\n";

    private final ChatClient chatClient;
    private final int maxSubProblems;
    private final String decompositionTemplate;
    private final String solvingTemplate;

    /**
     * Creates a solver from the given builder state. Use {@link #builder(ChatClient)} to
     * obtain a builder.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected LeastToMostSolver(final Builder builder) {
        this.chatClient = Objects.requireNonNull(builder.chatClient, "ChatClient must not be null");
        this.maxSubProblems = Math.max(1, builder.maxSubProblems);
        this.decompositionTemplate = builder.decompositionTemplate != null
                ? builder.decompositionTemplate : DEFAULT_DECOMPOSITION_TEMPLATE;
        this.solvingTemplate = builder.solvingTemplate != null
                ? builder.solvingTemplate : DEFAULT_SOLVING_TEMPLATE;
    }

    /**
     * Returns a fluent builder for configuring a {@link LeastToMostSolver}.
     *
     * @param chatClient the {@link ChatClient} used for all LLM calls; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Solves {@code problem} using the Least-to-Most strategy.
     *
     * <p>Returns the answer to the final sub-problem, which by construction incorporates
     * all earlier partial answers. Returns {@code null} if the decomposition call fails or
     * produces no sub-problems.
     *
     * @param problem the complex task to solve; never {@code null}
     * @return the final answer incorporating all sub-problem resolutions, or {@code null}
     *         on failure
     */
    @Nullable
    public String solve(@NonNull final String problem) {
        Objects.requireNonNull(problem, "Problem must not be null");

        List<String> subProblems = decompose(problem);
        if (subProblems.isEmpty()) {
            log.warn("LeastToMostSolver: decomposition produced no sub-problems for problem (len={})",
                    problem.length());
            return null;
        }

        log.debug("LeastToMostSolver: decomposed into {} sub-problems", subProblems.size());

        List<String> establishedFacts = new ArrayList<>();
        String lastAnswer = null;

        for (int i = 0; i < subProblems.size(); i++) {
            String subProblem = subProblems.get(i);
            String answer = solveSubProblem(problem, subProblem, establishedFacts);

            if (answer == null || answer.isBlank()) {
                log.warn("LeastToMostSolver: sub-problem {} failed or returned blank, stopping", i + 1);
                break;
            }

            lastAnswer = answer;
            establishedFacts.add(subProblem + " → " + answer.trim());
            log.debug("LeastToMostSolver: sub-problem {}/{} solved ({} chars)",
                    i + 1, subProblems.size(), answer.length());
        }

        return lastAnswer;
    }

    // ─── Protected extension points ───────────────────────────────────────────

    /**
     * Parses the raw decomposition response text into an ordered list of sub-problem strings.
     *
     * <p>The default implementation splits on newlines, strips common list prefixes
     * ({@code 1.}, {@code -}, {@code *}, {@code •}), and discards blank lines.
     * Override to handle structured outputs such as JSON arrays.
     *
     * @param decompositionResponse the raw text returned by the decomposition LLM call;
     *                              never {@code null}
     * @return an ordered, non-null, possibly empty list of sub-problem descriptions
     */
    @NonNull
    protected List<String> parseSubProblems(@NonNull final String decompositionResponse) {
        List<String> parsed = Arrays.stream(decompositionResponse.split("\\n"))
                .map(String::trim)
                .map(line -> line.replaceFirst("^\\d+[.)\\s]+", ""))
                .map(line -> line.replaceFirst("^[-*•]\\s*", ""))
                .filter(line -> !line.isBlank())
                .limit(maxSubProblems)
                .toList();
        return Objects.requireNonNull(parsed, "Parsed sub-problems must not be null");
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<String> decompose(final String problem) {
        try {
            String prompt = decompositionTemplate.replace("{problem}", problem);
            String response = chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            return parseSubProblems(response);
        } catch (Exception ex) {
            log.warn("LeastToMostSolver: decomposition LLM call failed: {}", ex.getMessage());
            return List.of();
        }
    }

    @Nullable
    private String solveSubProblem(
            final String problem,
            final String subProblem,
            final List<String> establishedFacts) {
        try {
            String contextBlock = establishedFacts.isEmpty()
                    ? ""
                    : CONTEXT_PREFIX + String.join("\n", establishedFacts) + "\n\n";

            String prompt = solvingTemplate
                    .replace("{problem}", problem)
                    .replace("{subproblem}", subProblem)
                    .replace("{context}", contextBlock);

            return chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
        } catch (Exception ex) {
            log.warn("LeastToMostSolver: sub-problem LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link LeastToMostSolver} with customised settings.
     * Obtain an instance via {@link LeastToMostSolver#builder(ChatClient)}.
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private int maxSubProblems = DEFAULT_MAX_SUB_PROBLEMS;
        @Nullable
        private String decompositionTemplate;
        @Nullable
        private String solvingTemplate;

        private Builder(final ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
        }

        /**
         * Sets the maximum number of sub-problems to decompose into and solve.
         *
         * <p>Sub-problems beyond this limit are silently dropped. Values less than 1 are
         * clamped to 1.
         *
         * @param maxSubProblems the sub-problem cap (default: 8)
         * @return this builder
         */
        public Builder maxSubProblems(final int maxSubProblems) {
            this.maxSubProblems = maxSubProblems;
            return this;
        }

        /**
         * Overrides the prompt used to decompose the problem into sub-problems.
         *
         * <p>The template must contain the placeholder {@code {problem}}. The LLM response
         * is parsed by {@link #parseSubProblems(String)}.
         *
         * @param decompositionTemplate the decomposition prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder decompositionTemplate(@Nullable final String decompositionTemplate) {
            this.decompositionTemplate = decompositionTemplate;
            return this;
        }

        /**
         * Overrides the prompt used to solve each sub-problem.
         *
         * <p>The template may contain the following placeholders:
         * <ul>
         *   <li>{@code {problem}} — the original problem statement</li>
         *   <li>{@code {subproblem}} — the current sub-problem to solve</li>
         *   <li>{@code {context}} — the previously established facts block (may be empty)</li>
         * </ul>
         *
         * @param solvingTemplate the solving prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder solvingTemplate(@Nullable final String solvingTemplate) {
            this.solvingTemplate = solvingTemplate;
            return this;
        }

        /**
         * Constructs the {@link LeastToMostSolver} from the current builder state.
         *
         * @return a fully configured solver; never {@code null}
         */
        public LeastToMostSolver build() {
            return new LeastToMostSolver(this);
        }
    }
}
