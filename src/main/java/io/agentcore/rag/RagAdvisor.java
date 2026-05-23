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
package io.agentcore.rag;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring AI {@code CallAdvisor} that augments each LLM request with documents retrieved
 * from a domain-specific knowledge source before the prompt reaches the model.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Extracts the first {@code UserMessage} from the incoming {@link org.springframework.ai.chat.prompt.Prompt}
 *       and uses its text as the retrieval query.</li>
 *   <li>Calls {@link AgentRetriever#retrieve(String, Map)} with the query and the request
 *       context map (which may contain tenant IDs or category filters).</li>
 *   <li>Filters results to those whose {@link RetrievedDocument#score} meets or
 *       exceeds {@code minScore}, then caps the list at {@code maxDocuments}.</li>
 *   <li>Formats each surviving document with {@link RetrievedDocument#toPromptFragment()}
 *       and wraps the block in configurable prefix/suffix delimiters.</li>
 *   <li>Appends the block to the existing {@code SystemMessage}, or inserts a new one at
 *       the head of the message list when no system message is present.</li>
 *   <li>Passes the augmented request to the next advisor in the chain unchanged in all
 *       other respects.</li>
 * </ol>
 *
 * <p>If the user query is blank, retrieval fails, or no documents pass the score threshold,
 * the original request is forwarded without modification — the LLM call always proceeds.
 *
 * <h3>Prompt injection placement</h3>
 * <p>Documents are injected into the <em>system</em> prompt so the LLM sees retrieved
 * context as authoritative background knowledge, clearly separated from the user's turn.
 *
 * <h3>Advisor chain position</h3>
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE + 10} so retrieval completes before any
 * LLM cost is incurred, but after filters that might short-circuit the call (e.g. rate
 * limiters).
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * @Bean
 * public RagAdvisor ragAdvisor(AgentRetriever retriever) {
 *     return RagAdvisor.builder(retriever)
 *             .maxDocuments(5)
 *             .minScore(0.75)
 *             .contextPrefix("--- RELEVANT KNOWLEDGE ---\n")
 *             .contextSuffix("\n--- END OF KNOWLEDGE ---\n")
 *             .build();
 * }
 * }</pre>
 *
 * @see AgentRetriever
 * @see RetrievedDocument
 */
@Slf4j
public class RagAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final int DEFAULT_MAX_DOCUMENTS = 5;
    private static final double DEFAULT_MIN_SCORE = 0.0;
    private static final String DEFAULT_CONTEXT_PREFIX =
            "\n\n--- RELEVANT CONTEXT ---\n";
    private static final String DEFAULT_CONTEXT_SUFFIX =
            "\n--- END OF CONTEXT ---\n\n";

    private final AgentRetriever retriever;
    private final int maxDocuments;
    private final double minScore;
    private final String contextPrefix;
    private final String contextSuffix;
    private final int order;

    /**
     * Creates a RAG advisor with all default settings (max 5 documents, no score
     * threshold, standard delimiters, {@code HIGHEST_PRECEDENCE + 10} order).
     *
     * @param retriever the document retriever to use; never {@code null}
     */
    public RagAdvisor(final AgentRetriever retriever) {
        this(retriever, DEFAULT_MAX_DOCUMENTS, DEFAULT_MIN_SCORE,
                DEFAULT_CONTEXT_PREFIX, DEFAULT_CONTEXT_SUFFIX, DEFAULT_ORDER);
    }

    /**
     * Full constructor for programmatic configuration. Prefer {@link #builder(AgentRetriever)}
     * for improved readability when more than one parameter is customised.
     *
     * @param retriever     the document retriever; never {@code null}
     * @param maxDocuments  maximum number of documents to inject; clamped to at least 1
     * @param minScore      minimum relevance score in [0.0, 1.0]; 0.0 includes all results
     * @param contextPrefix text prepended before the document block in the system prompt;
     *                      {@code null} falls back to the default delimiter
     * @param contextSuffix text appended after the document block in the system prompt;
     *                      {@code null} falls back to the default delimiter
     * @param order         the advisor chain order; lower values run first
     */
    public RagAdvisor(
            final AgentRetriever retriever,
            final int maxDocuments,
            final double minScore,
            final String contextPrefix,
            final String contextSuffix,
            final int order) {
        this.retriever = Objects.requireNonNull(retriever, "Retriever must not be null");
        this.maxDocuments = Math.max(1, maxDocuments);
        this.minScore = minScore;
        this.contextPrefix = contextPrefix != null ? contextPrefix : DEFAULT_CONTEXT_PREFIX;
        this.contextSuffix = contextSuffix != null ? contextSuffix : DEFAULT_CONTEXT_SUFFIX;
        this.order = order;
    }

    /**
     * Returns a fluent builder for configuring a {@link RagAdvisor}.
     *
     * @param retriever the document retriever to use; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final AgentRetriever retriever) {
        return new Builder(retriever);
    }

    @Override
    public @NonNull String getName() {
        return "RagAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Retrieves relevant documents for the user's message and injects them into the system
     * prompt before forwarding the augmented request to the next advisor in the chain.
     *
     * <p>When the user query is blank, no documents pass the score threshold, or the
     * retriever throws an exception, the original request is forwarded unchanged so the
     * LLM call always proceeds.
     *
     * @param request the incoming chat client request containing the user message and context
     * @param chain   the remaining advisor chain to call after augmentation
     * @return the {@link ChatClientResponse} from the downstream chain
     */
    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest request,
            final @NonNull CallAdvisorChain chain) {

        // Extract user message for retrieval query
        String userQuery = extractUserQuery(request);
        if (userQuery == null || userQuery.isBlank()) {
            return chain.nextCall(request);
        }

        // Retrieve documents
        Map<String, Object> context = request.context();
        List<RetrievedDocument> docs = retrieveDocuments(userQuery, context);
        if (docs.isEmpty()) {
            log.debug("RAG: no documents retrieved for query (len={})", userQuery.length());
            return chain.nextCall(request);
        }

        log.debug("RAG: retrieved {} documents for query (len={})", docs.size(), userQuery.length());

        // Inject documents into system prompt
        ChatClientRequest augmented = augmentRequest(request, docs);
        return chain.nextCall(Objects.requireNonNull(augmented, "Augmented request must not be null"));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String extractUserQuery(final ChatClientRequest request) {
        Prompt prompt = request.prompt();
        return prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(msg -> ((UserMessage) msg).getText())
                .findFirst()
                .orElse(null);
    }

    private List<RetrievedDocument> retrieveDocuments(
            final String query, final Map<String, Object> context) {
        try {
            return retriever.retrieve(query, context).stream()
                    .filter(doc -> doc.getScore() == null || doc.getScore() >= minScore)
                    .limit(maxDocuments)
                    .toList();
        } catch (Exception ex) {
            log.warn("RAG retrieval failed for query (len={}): {}", query.length(), ex.getMessage());
            return List.of();
        }
    }

    private ChatClientRequest augmentRequest(
            final ChatClientRequest request, final List<RetrievedDocument> docs) {
        String docsBlock = docs.stream()
                .map(RetrievedDocument::toPromptFragment)
                .collect(Collectors.joining("\n\n"));

        String injectedContext = contextPrefix + docsBlock + contextSuffix;

        // Append to existing system message, or add a new one
        Prompt originalPrompt = request.prompt();
        List<Message> messages = originalPrompt.getInstructions().stream()
                .map(msg -> {
                    if (msg instanceof SystemMessage sys) {
                        return (Message) new SystemMessage(sys.getText() + injectedContext);
                    }
                    return msg;
                })
                .collect(Collectors.toList());

        boolean hadSystem = originalPrompt.getInstructions().stream()
                .anyMatch(SystemMessage.class::isInstance);
        if (!hadSystem) {
            messages.add(0, new SystemMessage(injectedContext));
        }

        Prompt augmentedPrompt = new Prompt(messages, originalPrompt.getOptions());
        return ChatClientRequest.builder()
                .prompt(augmentedPrompt)
                .context(request.context())
                .build();
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link RagAdvisor} with customised settings.
     * Obtain an instance via {@link RagAdvisor#builder(AgentRetriever)}.
     */
    public static final class Builder {

        private final AgentRetriever retriever;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private double minScore = DEFAULT_MIN_SCORE;
        private String contextPrefix = DEFAULT_CONTEXT_PREFIX;
        private String contextSuffix = DEFAULT_CONTEXT_SUFFIX;
        private int order = DEFAULT_ORDER;

        private Builder(final AgentRetriever retriever) {
            this.retriever = Objects.requireNonNull(retriever, "Retriever must not be null");
        }

        /**
         * Sets the maximum number of documents to inject into the system prompt.
         *
         * @param maxDocuments the cap; values less than 1 are clamped to 1 (default: 5)
         * @return this builder
         */
        public Builder maxDocuments(final int maxDocuments) {
            this.maxDocuments = maxDocuments;
            return this;
        }

        /**
         * Sets the minimum relevance score a document must meet to be injected.
         *
         * @param minScore threshold in [0.0, 1.0]; {@code 0.0} includes all documents
         *                 regardless of score (default: 0.0)
         * @return this builder
         */
        public Builder minScore(final double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Sets the text inserted before the document block in the system prompt.
         *
         * @param prefix the delimiter prefix; {@code null} restores the default
         * @return this builder
         */
        public Builder contextPrefix(final String prefix) {
            this.contextPrefix = prefix;
            return this;
        }

        /**
         * Sets the text appended after the document block in the system prompt.
         *
         * @param suffix the delimiter suffix; {@code null} restores the default
         * @return this builder
         */
        public Builder contextSuffix(final String suffix) {
            this.contextSuffix = suffix;
            return this;
        }

        /**
         * Sets the position of this advisor in the {@link org.springframework.core.Ordered}
         * advisor chain. Lower values run earlier.
         *
         * @param order the chain order (default: {@code Ordered.HIGHEST_PRECEDENCE + 10})
         * @return this builder
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        /**
         * Constructs the {@link RagAdvisor} from the current builder state.
         *
         * @return a fully configured {@link RagAdvisor}; never {@code null}
         */
        public RagAdvisor build() {
            return new RagAdvisor(retriever, maxDocuments, minScore, contextPrefix, contextSuffix, order);
        }
    }
}
