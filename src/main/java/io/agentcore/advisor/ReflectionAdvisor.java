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
package io.agentcore.advisor;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring AI {@code CallAdvisor} that implements both <em>Self-Reflection</em> and the
 * <em>Refinement Loop</em> reasoning strategies from the Oracle DevWeek SF 2026 slides.
 *
 * <h3>How it works</h3>
 * <p>After the initial LLM call produces a draft response:
 * <ol>
 *   <li>A <b>critic</b> LLM call evaluates the draft's accuracy, completeness, and
 *       clarity, assigning a numeric score between 0 and 10.</li>
 *   <li>If the score meets {@code scoreThreshold} the draft is returned immediately —
 *       no unnecessary extra calls.</li>
 *   <li>If the score is below the threshold and {@code iterations < maxIterations}, a
 *       <b>refiner</b> LLM call rewrites the draft incorporating the critique feedback.</li>
 *   <li>Steps 1-3 repeat until the threshold is met or {@code maxIterations} is exhausted.</li>
 * </ol>
 *
 * <p>Benchmark reference (Oracle slides p.8): self-reflection improved analytical reasoning
 * accuracy from 33% to 76% — a 43-point gain — and SAT English from 93% to 98%.
 *
 * <h3>Advisor chain position</h3>
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE + 25} — after {@link ThinkingAdvisor} at
 * {@code + 20} but before the LLM, so the draft it intercepts already includes fully
 * assembled memory and RAG context. The refined response propagates back through
 * {@link MemoryAdvisor} (WORKFLOW write) and {@link BaseAuditAdvisor} (CALL_RESULT log)
 * so both see the final, improved output.
 *
 * <h3>Scoring</h3>
 * <p>The default {@link #extractScore} implementation parses the pattern
 * {@code Score: X/10} from the last line of the critique. Override this method to use
 * a domain-specific scoring scheme (sentiment, fact-check verdict, etc.).
 *
 * <h3>Registration example</h3>
 * <pre>{@code
 * @Bean
 * public ReflectionAdvisor reflectionAdvisor(ChatClient chatClient) {
 *     return ReflectionAdvisor.builder(chatClient)
 *             .maxIterations(2)
 *             .scoreThreshold(7.5)
 *             .build();
 * }
 * }</pre>
 *
 * <h3>Custom scoring example</h3>
 * <pre>{@code
 * public class MyReflectionAdvisor extends ReflectionAdvisor {
 *
 *     public MyReflectionAdvisor(ChatClient client) {
 *         super(ReflectionAdvisor.builder(client));
 *     }
 *
 *     @Override
 *     protected double extractScore(String critique) {
 *         // Return 10 when critique contains "APPROVED", 0 otherwise
 *         return critique.contains("APPROVED") ? 10.0 : 0.0;
 *     }
 * }
 * }</pre>
 *
 * @see io.agentcore.reasoning.ReasoningStrategy#SELF_REFLECTION
 * @see io.agentcore.reasoning.ReasoningStrategy#REFINEMENT_LOOP
 */
@Slf4j
public class ReflectionAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 25;
    private static final int DEFAULT_MAX_ITERATIONS = 2;
    private static final double DEFAULT_SCORE_THRESHOLD = 7.0;
    private static final double SCORE_NOT_FOUND = -1.0;

    private static final Pattern SCORE_PATTERN =
            Pattern.compile("\\bScore\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*10\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_CRITIC_PROMPT_TEMPLATE = """
            You are a critical evaluator. Review the following response to the user's request.

            User request: {task}

            Response to evaluate: {draft}

            Evaluate on accuracy, completeness, clarity, and helpfulness. List specific improvements needed, then on the final line write ONLY: Score: X/10
            """;

    private static final String DEFAULT_REFINER_PROMPT_TEMPLATE = """
            Improve the following response based on the critique provided.

            Original request: {task}

            Current response: {draft}

            Critique and required improvements: {critique}

            Provide an improved response that directly addresses all critique points.
            """;

    private final ChatClient chatClient;
    private final int maxIterations;
    private final double scoreThreshold;
    private final String criticPromptTemplate;
    private final String refinerPromptTemplate;
    private final int order;

    /**
     * Creates a reflection advisor from the given builder state. Use
     * {@link #builder(ChatClient)} to obtain a builder.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected ReflectionAdvisor(final Builder builder) {
        this.chatClient = Objects.requireNonNull(builder.chatClient, "ChatClient must not be null");
        this.maxIterations = Math.max(1, builder.maxIterations);
        this.scoreThreshold = builder.scoreThreshold;
        this.criticPromptTemplate = builder.criticPromptTemplate != null
                ? builder.criticPromptTemplate : DEFAULT_CRITIC_PROMPT_TEMPLATE;
        this.refinerPromptTemplate = builder.refinerPromptTemplate != null
                ? builder.refinerPromptTemplate : DEFAULT_REFINER_PROMPT_TEMPLATE;
        this.order = builder.order;
    }

    /**
     * Returns a fluent builder for configuring a {@link ReflectionAdvisor}.
     *
     * @param chatClient the {@link ChatClient} used for critic and refiner LLM calls;
     *                   never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final ChatClient chatClient) {
        return new Builder(chatClient);
    }

    @Override
    public @NonNull String getName() {
        return "ReflectionAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Obtains the initial draft response from the advisor chain, then applies the
     * reflection/refinement loop before returning.
     *
     * <p>If the user query is blank, the draft score meets the threshold on the first
     * critique, or any inner LLM call fails, the best available response is returned so
     * the overall request always completes.
     *
     * @param request the incoming chat client request
     * @param chain   the remaining advisor chain
     * @return the (possibly refined) {@link ChatClientResponse}; never {@code null}
     */
    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest request,
            final @NonNull CallAdvisorChain chain) {

        ChatClientResponse draftResponse = chain.nextCall(request);

        String userQuery = extractUserQuery(request);
        if (userQuery == null || userQuery.isBlank()) {
            return draftResponse;
        }

        String draft = extractText(draftResponse);
        if (draft == null || draft.isBlank()) {
            return draftResponse;
        }

        return Objects.requireNonNull(
                runRefinementLoop(draftResponse, draft, userQuery),
                "Refinement loop response must not be null");
    }

    // ─── Protected extension point ────────────────────────────────────────────

    /**
     * Extracts a numeric quality score from a critic's response text.
     *
     * <p>The default implementation searches for the pattern {@code Score: X/10}
     * (case-insensitive) anywhere in {@code critiqueText} and returns the parsed
     * value. Returns {@code -1.0} when the pattern is not found, causing the
     * refinement loop to continue regardless of content quality.
     *
     * <p>Override to implement domain-specific scoring — for example, extracting a
     * JSON {@code {"score": 8.5}} field, a keyword verdict, or a semantic similarity
     * measure against a gold answer.
     *
     * @param critiqueText the full text returned by the critic LLM call; never {@code null}
     * @return the quality score; typically in {@code [0, 10]}; {@code -1.0} signals
     *         that no score could be parsed (treated as below threshold)
     */
    protected double extractScore(final String critiqueText) {
        Matcher matcher = SCORE_PATTERN.matcher(critiqueText);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ex) {
                log.debug("ReflectionAdvisor: could not parse score from pattern match: {}", matcher.group(1));
            }
        }
        return SCORE_NOT_FOUND;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private ChatClientResponse runRefinementLoop(
            final ChatClientResponse draftResponse,
            final String initialDraft,
            final String userQuery) {

        String draft = initialDraft;
        ChatResponse lastChatResponse = draftResponse.chatResponse();
        boolean didRefine = false;

        boolean stopRefinement = false;
        for (int iteration = 0; iteration < maxIterations && !stopRefinement; iteration++) {
            String critique = invokeCritic(userQuery, draft);
            if (critique == null) {
                log.warn("ReflectionAdvisor: critic call returned null on iteration={}, "
                        + "returning best response so far", iteration);
                stopRefinement = true;
            }

            if (!stopRefinement) {
                double score = extractScore(critique);
                log.debug("ReflectionAdvisor: iteration={} score={} threshold={}", iteration + 1, score, scoreThreshold);

                if (score >= scoreThreshold) {
                    log.debug("ReflectionAdvisor: score threshold met — returning draft without further refinement");
                    stopRefinement = true;
                }

                if (!stopRefinement) {
                    ChatResponse refined = invokeRefiner(userQuery, draft, critique);
                    if (refined == null) {
                        log.warn("ReflectionAdvisor: refiner call returned null on iteration={}, "
                                + "returning best response so far", iteration);
                        stopRefinement = true;
                    }

                    if (!stopRefinement) {
                        String refinedText = extractTextFromChatResponse(refined);
                        if (refinedText == null || refinedText.isBlank()) {
                            log.warn("ReflectionAdvisor: refiner produced blank text on iteration={}", iteration);
                            stopRefinement = true;
                        } else {
                            draft = refinedText;
                            lastChatResponse = refined;
                            didRefine = true;
                            log.debug("ReflectionAdvisor: refined response ({} chars) on iteration={}",
                                    draft.length(), iteration + 1);
                        }
                    }
                }
            }
        }

        if (!didRefine) {
            return draftResponse;
        }

        return ChatClientResponse.builder()
                .chatResponse(lastChatResponse)
                .context(draftResponse.context())
                .build();
    }

    @Nullable
    private String invokeCritic(final String userQuery, final String draft) {
        try {
            String prompt = criticPromptTemplate
                    .replace("{task}", userQuery)
                    .replace("{draft}", draft);
            return chatClient.prompt()
                    .user(Objects.requireNonNull(prompt, "Critic prompt must not be null"))
                    .call()
                    .content();
        } catch (Exception ex) {
            log.warn("ReflectionAdvisor: critic LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    @Nullable
    private ChatResponse invokeRefiner(
            final String userQuery, final String draft, final String critique) {
        try {
            String prompt = refinerPromptTemplate
                    .replace("{task}", userQuery)
                    .replace("{draft}", draft)
                    .replace("{critique}", critique);
            return chatClient.prompt()
                    .user(Objects.requireNonNull(prompt, "Refiner prompt must not be null"))
                    .call()
                    .chatResponse();
        } catch (Exception ex) {
            log.warn("ReflectionAdvisor: refiner LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    @Nullable
    private String extractUserQuery(final ChatClientRequest request) {
        Prompt prompt = request.prompt();
        return prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(msg -> ((UserMessage) msg).getText())
                .findFirst()
                .orElse(null);
    }

    @Nullable
    private String extractText(final ChatClientResponse response) {
        if (response == null) {
            return null;
        }
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    @Nullable
    private String extractTextFromChatResponse(final ChatResponse chatResponse) {
        try {
            return chatResponse.getResult().getOutput().getText();
        } catch (Exception ex) {
            log.debug("ReflectionAdvisor: could not extract text from ChatResponse: {}", ex.getMessage());
            return null;
        }
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link ReflectionAdvisor} with customised settings.
     * Obtain an instance via {@link ReflectionAdvisor#builder(ChatClient)}.
     */
    public static final class Builder {

        private final ChatClient chatClient;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private double scoreThreshold = DEFAULT_SCORE_THRESHOLD;
        private String criticPromptTemplate = DEFAULT_CRITIC_PROMPT_TEMPLATE;
        private String refinerPromptTemplate = DEFAULT_REFINER_PROMPT_TEMPLATE;
        private int order = DEFAULT_ORDER;

        private Builder(final ChatClient chatClient) {
            this.chatClient = Objects.requireNonNull(chatClient, "ChatClient must not be null");
        }

        /**
         * Sets the maximum number of generate → critique → refine iterations.
         *
         * <p>The loop exits early when the score threshold is met. Values less than 1 are
         * clamped to 1 (at least one critique is always performed).
         *
         * @param maxIterations the iteration cap (default: 2)
         * @return this builder
         */
        public Builder maxIterations(final int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Sets the minimum quality score (0–10) that the critic must assign before
         * refinement stops.
         *
         * <p>A threshold of {@code 10.0} forces the maximum number of refinement passes.
         * A threshold of {@code 0.0} accepts the first draft unconditionally.
         *
         * @param scoreThreshold the quality gate; typical range [0.0, 10.0] (default: 7.0)
         * @return this builder
         */
        public Builder scoreThreshold(final double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * Overrides the critic prompt template.
         *
         * <p>The template may contain the following placeholders:
         * <ul>
         *   <li>{@code {task}} — the original user query</li>
         *   <li>{@code {draft}} — the current draft response</li>
         * </ul>
         * The final line <b>must</b> end with {@code Score: X/10} for the default
         * {@link #extractScore} implementation to work. Override {@link #extractScore}
         * when using a template that produces scores in a different format.
         *
         * @param criticPromptTemplate the critic prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder criticPromptTemplate(final String criticPromptTemplate) {
            this.criticPromptTemplate = criticPromptTemplate;
            return this;
        }

        /**
         * Overrides the refiner prompt template.
         *
         * <p>The template may contain the following placeholders:
         * <ul>
         *   <li>{@code {task}} — the original user query</li>
         *   <li>{@code {draft}} — the draft response to improve</li>
         *   <li>{@code {critique}} — the critique produced by the critic call</li>
         * </ul>
         *
         * @param refinerPromptTemplate the refiner prompt; {@code null} restores the default
         * @return this builder
         */
        public Builder refinerPromptTemplate(final String refinerPromptTemplate) {
            this.refinerPromptTemplate = refinerPromptTemplate;
            return this;
        }

        /**
         * Sets the advisor chain position. Lower values run earlier.
         *
         * @param order the chain order (default: {@code Ordered.HIGHEST_PRECEDENCE + 25})
         * @return this builder
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        /**
         * Constructs the {@link ReflectionAdvisor} from the current builder state.
         *
         * @return a fully configured {@link ReflectionAdvisor}; never {@code null}
         */
        public ReflectionAdvisor build() {
            return new ReflectionAdvisor(this);
        }
    }
}
