/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.reasoning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements the <em>Tree of Thoughts</em> (ToT) reasoning strategy using a beam-search
 * algorithm over candidate thought branches.
 *
 * <h3>Why Tree of Thoughts outperforms linear Chain-of-Thought</h3>
 * <p>Chain-of-Thought commits to a single reasoning path. When the chosen path is
 * suboptimal the model has no way to backtrack. Tree of Thoughts explores multiple
 * divergent reasoning branches simultaneously, evaluates each branch with a critic, and
 * prunes weaker branches — keeping only the most promising candidates at each depth
 * level. Oracle benchmarks (slides p.7) show ToT reaching 74% on symbolic reasoning tasks
 * where GPT-4 with CoT scores only 4%.
 *
 * <h3>Algorithm</h3>
 * <pre>
 * beam  = [initial problem]
 * for depth in 0..maxDepth:
 *     candidates = []
 *     for each node in beam:
 *         generate beamWidth thoughts in parallel
 *         evaluate + score each thought in parallel
 *         candidates += all (thought, score) pairs
 *     beam = top beamWidth candidates by score
 * return best leaf's thought text
 * </pre>
 *
 * <h3>Execution model</h3>
 * <p>Within each depth level, thought generation and evaluation calls run concurrently on
 * Java 21 virtual threads. A configurable {@code timeoutSeconds} (default 120 s) prevents
 * indefinite blocking across the full tree.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * TreeOfThoughtsExplorer tot = TreeOfThoughtsExplorer.builder(chatClient)
 *         .beamWidth(3)
 *         .maxDepth(2)
 *         .build();
 *
 * String answer = tot.explore(
 *     "Design a discount strategy that maximises revenue while minimising " +
 *     "customer churn for Q3.");
 * }</pre>
 *
 * <h3>Scoring</h3>
 * <p>The default {@link #extractScore(String)} parses {@code Score: X/10} from the
 * evaluator response. Override for domain-specific scoring.
 *
 * @see ReasoningStrategy#TREE_OF_THOUGHTS
 */
@Slf4j
public class TreeOfThoughtsExplorer {

    private static final int DEFAULT_BEAM_WIDTH = 3;
    private static final int DEFAULT_MAX_DEPTH = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final double SCORE_NOT_FOUND = 0.0;

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("\\bScore\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*10\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_THOUGHT_GENERATION_TEMPLATE = """
            Problem: {problem}

            Reasoning so far: {context}

            Generate {count} distinct next reasoning steps or solution approaches. Each step should
            explore a meaningfully different direction. Separate each thought with the delimiter:
            ---THOUGHT---
            """;

    private static final String DEFAULT_EVALUATION_TEMPLATE = """
            Problem: {problem}

            Candidate reasoning path:
            {thought}

            Evaluate this reasoning path for correctness, insight, and likelihood of leading to a
            good final answer. On the last line write ONLY: Score: X/10
            """;

    private static final String DEFAULT_CONCLUSION_TEMPLATE = """
            Problem: {problem}

            Best reasoning path explored:
            {thought}

            Based on this reasoning path, provide a clear, concise final answer:
            """;

    private static final String THOUGHT_DELIMITER = "---THOUGHT---";

    private final ChatClient chatClient;
    private final int beamWidth;
    private final int maxDepth;
    private final int timeoutSeconds;
    private final String thoughtGenerationTemplate;
    private final String evaluationTemplate;
    private final String conclusionTemplate;

    /**
     * Creates an explorer from the given builder state. Use {@link #builder(ChatClient)}
     * to obtain a builder.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected TreeOfThoughtsExplorer(final Builder builder) {
        this.chatClient = Objects.requireNonNull(builder.chatClient, "ChatClient must not be null");
        this.beamWidth = Math.max(1, builder.beamWidth);
        this.maxDepth = Math.max(1, builder.maxDepth);
        this.timeoutSeconds = Math.max(1, builder.timeoutSeconds);
        this.thoughtGenerationTemplate = builder.thoughtGenerationTemplate != null
                ? builder.thoughtGenerationTemplate : DEFAULT_THOUGHT_GENERATION_TEMPLATE;
        this.evaluationTemplate = builder.evaluationTemplate != null
                ? builder.evaluationTemplate : DEFAULT_EVALUATION_TEMPLATE;
        this.conclusionTemplate = builder.conclusionTemplate != null
                ? builder.conclusionTemplate : DEFAULT_CONCLUSION_TEMPLATE;
    }

    /**
     * Returns a fluent builder for configuring a {@link TreeOfThoughtsExplorer}.
     *
     * @param chatClient the {@link ChatClient} used for all LLM calls; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Runs the Tree-of-Thoughts beam search for the given {@code problem} and returns the
     * final synthesised answer derived from the best-scoring reasoning path.
     *
     * <p>Returns {@code null} if no successful thought candidates are generated at any
     * depth level.
     *
     * @param problem the problem to explore; never {@code null}
     * @return the final answer derived from the best reasoning path, or {@code null} on
     *         failure
     */
    @Nullable
    public String explore(@NonNull final String problem) {
        Objects.requireNonNull(problem, "Problem must not be null");

        List<ScoredThought> beam = new ArrayList<>();
        beam.add(new ScoredThought("", 0.0));

        ScoredThought bestLeaf = null;

        for (int depth = 0; depth < maxDepth; depth++) {
            List<ScoredThought> nextBeam = expandAndEvaluate(problem, beam, depth);

            if (nextBeam.isEmpty()) {
                log.warn("TreeOfThoughtsExplorer: no candidates survived at depth={}, stopping", depth);
                break;
            }

            beam = nextBeam;
            bestLeaf = beam.get(0);
            log.debug("TreeOfThoughtsExplorer: depth={} beam size={} best score={}",
                    depth + 1, beam.size(), bestLeaf.score());
        }

        if (bestLeaf == null || bestLeaf.thought().isBlank()) {
            return null;
        }

        return concludeFromBestPath(problem, bestLeaf.thought());
    }

    // ─── Protected extension points ───────────────────────────────────────────

    /**
     * Parses the raw thought generation response into individual thought strings.
     *
     * <p>The default implementation splits on {@code ---THOUGHT---} delimiters and
     * discards blank segments. Override to handle custom formats.
     *
     * @param generationResponse the raw LLM response containing multiple thoughts;
     *                           never {@code null}
     * @return a non-null, possibly empty list of thought strings
     */
    @NonNull
    protected List<String> parseThoughts(@NonNull final String generationResponse) {
        List<String> thoughts = new ArrayList<>();
        for (String segment : generationResponse.split(THOUGHT_DELIMITER)) {
            String trimmed = segment.trim();
            if (!trimmed.isBlank()) {
                thoughts.add(trimmed);
            }
        }
        return thoughts;
    }

    /**
     * Extracts a numeric quality score from an evaluator response.
     *
     * <p>The default implementation parses {@code Score: X/10} (case-insensitive).
     * Returns {@code 0.0} when the pattern is not found. Override for domain-specific
     * scoring schemes.
     *
     * @param evaluationResponse the evaluator LLM response; never {@code null}
     * @return the quality score; typically in {@code [0, 10]}
     */
    protected double extractScore(@NonNull final String evaluationResponse) {
        Matcher matcher = SCORE_PATTERN.matcher(evaluationResponse);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ex) {
                log.debug("TreeOfThoughtsExplorer: could not parse score from: {}", matcher.group(1));
            }
        }
        return SCORE_NOT_FOUND;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private List<ScoredThought> expandAndEvaluate(
            final String problem,
            final List<ScoredThought> beam,
            final int depth) {

        CopyOnWriteArrayList<ScoredThought> candidates = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (ScoredThought parent : beam) {
                futures.add(executor.submit(() -> {
                    List<String> thoughts = generateThoughts(problem, parent.thought());
                    List<ScoredThought> scored = scoreThoughtsInParallel(problem, thoughts, parent.thought());
                    candidates.addAll(scored);
                }));
            }

            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            for (var future : futures) {
                long remaining = endTime - System.currentTimeMillis();
                try {
                    if (remaining > 0) {
                        future.get(remaining, TimeUnit.MILLISECONDS);
                    } else {
                        future.cancel(true);
                    }
                } catch (TimeoutException ex) {
                    log.warn("TreeOfThoughtsExplorer: expansion timed out at depth={}", depth);
                    future.cancel(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("TreeOfThoughtsExplorer: interrupted during expansion at depth={}", depth);
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (ExecutionException ex) {
                    log.debug("TreeOfThoughtsExplorer: expansion task failed: {}", ex.getMessage());
                }
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(ScoredThought::score).reversed())
                .limit(beamWidth)
                .toList();
    }

    private List<String> generateThoughts(final String problem, final String context) {
        try {
            String prompt = thoughtGenerationTemplate
                    .replace("{problem}", problem)
                    .replace("{context}", context.isBlank() ? "(none yet)" : context)
                    .replace("{count}", String.valueOf(beamWidth));
            String response = chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
            if (response == null || response.isBlank()) {
                return List.of();
            }
            return parseThoughts(response);
        } catch (Exception ex) {
            log.debug("TreeOfThoughtsExplorer: thought generation failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<ScoredThought> scoreThoughtsInParallel(
            final String problem,
            final List<String> thoughts,
            final String parentContext) {

        CopyOnWriteArrayList<ScoredThought> scored = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (String thought : thoughts) {
                final String fullPath = parentContext.isBlank()
                        ? thought
                        : parentContext + "\n→ " + thought;

                futures.add(executor.submit(() -> {
                    try {
                        double score = evaluateThought(problem, fullPath);
                        scored.add(new ScoredThought(fullPath, score));
                    } catch (Exception ex) {
                        log.debug("TreeOfThoughtsExplorer: evaluation failed for thought: {}", ex.getMessage());
                    }
                }));
            }

            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000L);
            for (var future : futures) {
                long remaining = endTime - System.currentTimeMillis();
                try {
                    if (remaining > 0) {
                        future.get(remaining, TimeUnit.MILLISECONDS);
                    } else {
                        future.cancel(true);
                    }
                } catch (TimeoutException ex) {
                    log.warn("TreeOfThoughtsExplorer: evaluation timed out");
                    future.cancel(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    futures.forEach(f -> f.cancel(true));
                    break;
                } catch (ExecutionException ex) {
                    log.debug("TreeOfThoughtsExplorer: evaluation task failed: {}", ex.getMessage());
                }
            }
        }

        return scored;
    }

    private double evaluateThought(final String problem, final String thought) {
        try {
            String prompt = evaluationTemplate
                    .replace("{problem}", problem)
                    .replace("{thought}", thought);
            String response = chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
            return response != null ? extractScore(response) : SCORE_NOT_FOUND;
        } catch (Exception ex) {
            log.debug("TreeOfThoughtsExplorer: evaluation LLM call failed: {}", ex.getMessage());
            return SCORE_NOT_FOUND;
        }
    }

    @Nullable
    private String concludeFromBestPath(final String problem, final String bestThought) {
        try {
            String prompt = conclusionTemplate
                    .replace("{problem}", problem)
                    .replace("{thought}", bestThought);
            return chatClient.prompt().user(Objects.requireNonNull(prompt, "Prompt must not be null")).call().content();
        } catch (Exception ex) {
            log.warn("TreeOfThoughtsExplorer: conclusion LLM call failed: {}", ex.getMessage());
            return bestThought;
        }
    }

    // ─── Internal record ──────────────────────────────────────────────────────

    private record ScoredThought(String thought, double score) {}

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link TreeOfThoughtsExplorer} with customised
     * settings. Obtain an instance via {@link TreeOfThoughtsExplorer#builder(ChatClient)}.
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private int beamWidth = DEFAULT_BEAM_WIDTH;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        @Nullable
        private String thoughtGenerationTemplate;
        @Nullable
        private String evaluationTemplate;
        @Nullable
        private String conclusionTemplate;

        private Builder(final ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
        }

        /**
         * Sets the beam width — the number of top-scoring candidate thoughts to keep at
         * each depth level, and the number of thoughts to generate per parent node.
         *
         * <p>Higher values explore a wider search space but incur more LLM calls per depth
         * level ({@code beamWidth²} evaluations). Values less than 1 are clamped to 1.
         *
         * @param beamWidth the beam width (default: 3)
         * @return this builder
         */
        public Builder beamWidth(final int beamWidth) {
            this.beamWidth = beamWidth;
            return this;
        }

        /**
         * Sets the maximum tree depth — the number of thought-expansion rounds before the
         * algorithm concludes from the best leaf.
         *
         * <p>Values less than 1 are clamped to 1. Total LLM calls grow as
         * {@code O(beamWidth² × depth)}, so keep depth ≤ 3 for most use cases.
         *
         * @param maxDepth the max tree depth (default: 2)
         * @return this builder
         */
        public Builder maxDepth(final int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /**
         * Sets the maximum wall-clock time for the entire exploration.
         *
         * <p>Virtual threads that exceed this budget are abandoned (their results are
         * excluded). Values less than 1 are clamped to 1 second.
         *
         * @param timeoutSeconds the timeout in seconds (default: 120)
         * @return this builder
         */
        public Builder timeoutSeconds(final int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * Overrides the thought generation prompt.
         *
         * <p>Must contain the placeholders {@code {problem}}, {@code {context}}, and
         * {@code {count}}. Thoughts in the response must be separated by
         * {@code ---THOUGHT---} for the default {@link #parseThoughts} to split them
         * correctly.
         *
         * @param thoughtGenerationTemplate the generation prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder thoughtGenerationTemplate(@Nullable final String thoughtGenerationTemplate) {
            this.thoughtGenerationTemplate = thoughtGenerationTemplate;
            return this;
        }

        /**
         * Overrides the thought evaluation prompt.
         *
         * <p>Must contain {@code {problem}} and {@code {thought}}. The final line should
         * produce {@code Score: X/10} for the default {@link #extractScore} to work.
         *
         * @param evaluationTemplate the evaluation prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder evaluationTemplate(@Nullable final String evaluationTemplate) {
            this.evaluationTemplate = evaluationTemplate;
            return this;
        }

        /**
         * Overrides the conclusion prompt used to derive the final answer from the best path.
         *
         * <p>Must contain {@code {problem}} and {@code {thought}}.
         *
         * @param conclusionTemplate the conclusion prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder conclusionTemplate(@Nullable final String conclusionTemplate) {
            this.conclusionTemplate = conclusionTemplate;
            return this;
        }

        /**
         * Constructs the {@link TreeOfThoughtsExplorer} from the current builder state.
         *
         * @return a fully configured explorer; never {@code null}
         */
        public TreeOfThoughtsExplorer build() {
            return new TreeOfThoughtsExplorer(this);
        }
    }
}
