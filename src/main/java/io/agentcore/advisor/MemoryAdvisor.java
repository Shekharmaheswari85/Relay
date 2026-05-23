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

import io.agentcore.memory.AgentMemoryManager;
import io.agentcore.memory.MemoryEntry;
import io.agentcore.memory.MemoryType;
import io.agentcore.memory.entity.EntityFact;
import io.agentcore.memory.entity.EntityMemoryStore;
import io.agentcore.memory.persona.PersonaStore;
import io.agentcore.session.SessionContextHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring AI {@code CallAdvisor} that forms the <em>harness engineering</em> layer for
 * agent memory — firing memory read (pre-LLM) and memory write (post-LLM) operations at
 * the correct point in every agent turn.
 *
 * <h3>Pre-call phase — memory injection</h3>
 * <p>Before the prompt reaches the model, {@code MemoryAdvisor} assembles a memory context
 * block from up to three sources (all null-safe):
 * <ol>
 *   <li><b>Persona fragment</b> — retrieved from {@link PersonaStore} using the request's
 *       {@code userId} context key. Surfaces user preferences, goals, and communication
 *       style so every response is personalised.</li>
 *   <li><b>Entity facts</b> — retrieved from {@link EntityMemoryStore} for the current
 *       session. Surfaces structured knowledge about products, stores, or customers that
 *       the agent has observed in prior turns.</li>
 *   <li><b>General memory</b> — retrieved from {@link AgentMemoryManager#assembleMemoryContext}
 *       which merges ENTITY, PERSONA, and WORKFLOW memories ranked by semantic similarity to
 *       the user's current message.</li>
 * </ol>
 * <p>When any fragment is non-empty the combined block is injected into the {@code SystemMessage}
 * (or a new one is prepended when no system message is present), wrapped in configurable
 * {@code injectPrefix} / {@code injectSuffix} delimiters.
 *
 * <h3>Post-call phase — memory write</h3>
 * <p>After the model responds:
 * <ol>
 *   <li>The Q/A exchange is stored as a {@link MemoryType#WORKFLOW} entry — up to 200 chars
 *       of the user query and 300 chars of the assistant response — so the agent can recall
 *       prior task patterns in future turns.</li>
 *   <li>{@link #extractEntities(String, String, String)} is called as an extension hook so
 *       subclasses can parse the response and persist entity facts without modifying the
 *       advisor chain.</li>
 * </ol>
 * <p>Post-call storage is skipped when either {@code sessionId} or {@code userId} is absent.
 *
 * <h3>Advisor chain position</h3>
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE + 5} — before {@code RagAdvisor} at
 * {@code + 10} and {@code ThinkingAdvisor} at {@code + 20}. This means memory context appears
 * <em>first</em> in the system prompt, immediately followed by the RAG knowledge block. The
 * LLM therefore sees personalisation context before domain documents, matching the cognitive
 * model of "know the user, then consult the facts".
 *
 * <h3>Registration example</h3>
 * <pre>{@code
 * @Bean
 * public MemoryAdvisor memoryAdvisor(AgentMemoryManager memoryManager,
 *                                    EntityMemoryStore entityStore,
 *                                    PersonaStore personaStore) {
 *     return MemoryAdvisor.builder(memoryManager)
 *             .entityMemoryStore(entityStore)
 *             .personaStore(personaStore)
 *             .maxWorkflowContentLength(600)
 *             .injectPrefix("\n\n[AGENT MEMORY]\n")
 *             .injectSuffix("\n[END AGENT MEMORY]\n\n")
 *             .build();
 * }
 * }</pre>
 *
 * <h3>Entity extraction extension point</h3>
 * <p>Override {@link #extractEntities(String, String, String)} in a subclass to implement
 * domain-specific entity extraction without touching the advisor chain:
 * <pre>{@code
 * public class MyMemoryAdvisor extends MemoryAdvisor {
 *     public MyMemoryAdvisor(AgentMemoryManager m, EntityMemoryStore e, PersonaStore p) {
 *         super(MemoryAdvisor.builder(m).entityMemoryStore(e).personaStore(p));
 *     }
 *     @Override
 *     protected void extractEntities(String responseText, String sessionId, String userId) {
 *         if (responseText.contains("out of stock")) {
 *             entityMemoryStore.storeFact(
 *                 EntityFact.of("unknown", "PRODUCT", "stockStatus", "OUT_OF_STOCK",
 *                               sessionId, userId));
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see AgentMemoryManager
 * @see EntityMemoryStore
 * @see PersonaStore
 * @see io.agentcore.rag.RagAdvisor
 */
@Slf4j
public class MemoryAdvisor implements CallAdvisor {

    private static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;
    private static final int DEFAULT_MAX_WORKFLOW_CONTENT_LENGTH = 500;
    private static final String DEFAULT_INJECT_PREFIX = "\n\n[AGENT MEMORY]\n";
    private static final String DEFAULT_INJECT_SUFFIX = "\n[END AGENT MEMORY]\n\n";

    private static final int QUERY_SNIPPET_LENGTH = 200;
    private static final int RESPONSE_SNIPPET_LENGTH = 300;

    /** Required memory manager; provides semantic recall and context assembly. */
    protected final AgentMemoryManager memoryManager;

    /** Optional entity memory store; provides structured entity fact recall. May be {@code null}. */
    protected final EntityMemoryStore entityMemoryStore;

    /** Optional persona store; provides per-user preference and goal context. May be {@code null}. */
    protected final PersonaStore personaStore;

    private final int maxWorkflowContentLength;
    private final String injectPrefix;
    private final String injectSuffix;
    private final int order;

    /**
     * Creates a memory advisor from a builder. Use {@link #builder(AgentMemoryManager)} to
     * obtain a builder instance.
     *
     * @param builder a fully configured builder; never {@code null}
     */
    protected MemoryAdvisor(final Builder builder) {
        this.memoryManager = Objects.requireNonNull(builder.memoryManager,
                "AgentMemoryManager must not be null");
        this.entityMemoryStore = builder.entityMemoryStore;
        this.personaStore = builder.personaStore;
        this.maxWorkflowContentLength = builder.maxWorkflowContentLength;
        this.injectPrefix = builder.injectPrefix != null ? builder.injectPrefix : DEFAULT_INJECT_PREFIX;
        this.injectSuffix = builder.injectSuffix != null ? builder.injectSuffix : DEFAULT_INJECT_SUFFIX;
        this.order = builder.order;
    }

    /**
     * Returns a fluent builder for configuring a {@link MemoryAdvisor}.
     *
     * @param memoryManager the required memory manager; never {@code null}
     * @return a new builder pre-populated with default values
     */
    public static Builder builder(final AgentMemoryManager memoryManager) {
        return new Builder(memoryManager);
    }

    @Override
    public @NonNull String getName() {
        return "MemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Injects assembled memory context into the system prompt (pre-call), forwards the
     * augmented request to the next advisor in the chain, then stores the Q/A exchange as
     * WORKFLOW memory and invokes the {@link #extractEntities} hook (post-call).
     *
     * <p>If memory assembly fails for any reason the original request is forwarded unchanged
     * so the LLM call always proceeds. Post-call storage failures are logged at {@code WARN}
     * level but never propagate to the caller.
     *
     * @param request the incoming chat client request containing the user message and context
     * @param chain   the remaining advisor chain to call after augmentation
     * @return the {@link ChatClientResponse} from the downstream chain; never {@code null}
     */
    @Override
    public @NonNull ChatClientResponse adviseCall(
            final @NonNull ChatClientRequest request,
            final @NonNull CallAdvisorChain chain) {

        // ── Pre-call: resolve session / user context ──────────────────────────
        String sessionId = SessionContextHolder.get();
        String userId = extractUserId(request.context());
        String userQuery = extractUserQuery(request);

        // ── Pre-call: assemble and inject memory context ──────────────────────
        ChatClientRequest augmented = request;
        try {
            String memoryBlock = assembleMemoryBlock(sessionId, userId, userQuery);
            if (memoryBlock != null && !memoryBlock.isBlank()) {
                augmented = injectIntoSystemPrompt(request, injectPrefix + memoryBlock + injectSuffix);
                log.debug("MemoryAdvisor: injected memory block ({} chars) for session={}",
                        memoryBlock.length(), sessionId);
            }
        } catch (Exception ex) {
            log.warn("MemoryAdvisor: pre-call memory assembly failed, proceeding without memory context: {}",
                    ex.getMessage());
        }

        // ── Delegate to next advisor / LLM ────────────────────────────────────
        ChatClientResponse response = Objects.requireNonNull(
                chain.nextCall(Objects.requireNonNull(augmented, "Augmented request must not be null")),
                "Advisor chain response must not be null");

        // ── Post-call: store Q/A exchange + trigger entity extraction ─────────
        if (sessionId != null && userId != null) {
            try {
                String assistantText = extractAssistantText(response);
                storeWorkflowMemory(userQuery, assistantText, sessionId, userId);
                extractEntities(assistantText, sessionId, userId);
            } catch (Exception ex) {
                log.warn("MemoryAdvisor: post-call memory write failed: {}", ex.getMessage());
            }
        }

        return response;
    }

    // ─── Protected extension point ────────────────────────────────────────────

    /**
     * Extension point for subclasses to implement entity extraction from the assistant response.
     *
     * <p>Override this method to parse the assistant response and call
     * {@link EntityMemoryStore#storeFact} to persist extracted entities. The base
     * implementation is a no-op.
     *
     * <p>Example — simple keyword-based extraction:
     * <pre>{@code
     * @Override
     * protected void extractEntities(String responseText, String sessionId, String userId) {
     *     if (responseText.contains("out of stock")) {
     *         entityMemoryStore.storeFact(
     *             EntityFact.of("unknown", "PRODUCT", "stockStatus", "OUT_OF_STOCK", sessionId, userId));
     *     }
     * }
     * }</pre>
     *
     * @param responseText the full assistant response text
     * @param sessionId    the current session identifier; may be {@code null}
     * @param userId       the current user identifier; may be {@code null}
     */
    protected void extractEntities(final String responseText,
                                    final String sessionId,
                                    final String userId) {
        // Default: no-op. Override in subclasses to add entity extraction logic.
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Assembles the raw memory content block (without prefix/suffix wrappers) from persona,
     * entity, and general memory sources. Returns {@code null} or a blank string when all
     * sources are empty.
     */
    private String assembleMemoryBlock(final String sessionId,
                                        final String userId,
                                        final String userQuery) {
        StringBuilder block = new StringBuilder();

        // 1. Persona fragment (per-user, cross-session)
        if (personaStore != null && userId != null) {
            try {
                String personaFragment = personaStore.getPersona(userId)
                        .map(p -> p.toPromptFragment())
                        .orElse("");
                if (!personaFragment.isBlank()) {
                    block.append(personaFragment);
                }
            } catch (Exception ex) {
                log.debug("MemoryAdvisor: persona retrieval failed for userId={}: {}", userId, ex.getMessage());
            }
        }

        // 2. Entity facts (per-session structured knowledge)
        if (entityMemoryStore != null && sessionId != null) {
            try {
                List<EntityFact> facts =
                        entityMemoryStore.recallForSession(sessionId);
                if (!facts.isEmpty()) {
                    block.append("\n[ENTITY FACTS]\n");
                    facts.forEach(fact -> block.append("- ").append(fact.toPromptFragment()).append("\n"));
                    block.append("[END ENTITY FACTS]\n");
                }
            } catch (Exception ex) {
                log.debug("MemoryAdvisor: entity recall failed for sessionId={}: {}", sessionId, ex.getMessage());
            }
        }

        // 3. General memory (semantic recall across ENTITY, PERSONA, WORKFLOW types)
        try {
            String generalMemory = memoryManager.assembleMemoryContext(sessionId, userId, userQuery);
            if (generalMemory != null && !generalMemory.isBlank()) {
                block.append(generalMemory);
            }
        } catch (Exception ex) {
            log.debug("MemoryAdvisor: general memory assembly failed: {}", ex.getMessage());
        }

        return block.toString();
    }

    /**
     * Injects {@code injectedBlock} into the system prompt of {@code request}.
     * Appends to an existing {@code SystemMessage} or prepends a new one when absent.
     */
    private ChatClientRequest injectIntoSystemPrompt(final ChatClientRequest request,
                                                      final @NonNull String injectedBlock) {
        String nonNullInjectedBlock = Objects.requireNonNull(injectedBlock, "Injected block must not be null");
        Prompt originalPrompt = request.prompt();
        List<Message> messages = originalPrompt.getInstructions().stream()
                .map(msg -> {
                    if (msg instanceof SystemMessage sys) {
                        return (Message) new SystemMessage(sys.getText() + nonNullInjectedBlock);
                    }
                    return msg;
                })
                .collect(Collectors.toList());

        boolean hadSystem = originalPrompt.getInstructions().stream()
                .anyMatch(SystemMessage.class::isInstance);
        if (!hadSystem) {
            messages.add(0, new SystemMessage(nonNullInjectedBlock));
        }

        Prompt augmentedPrompt = new Prompt(messages, originalPrompt.getOptions());
        return ChatClientRequest.builder()
                .prompt(augmentedPrompt)
                .context(request.context())
                .build();
    }

    /**
     * Stores the current Q/A exchange as a {@link MemoryType#WORKFLOW} entry in the
     * memory manager, capped at the configured content lengths.
     */
    private void storeWorkflowMemory(final String userQuery,
                                      final String assistantText,
                                      final String sessionId,
                                      final String userId) {
        String querySnippet = (userQuery != null && userQuery.length() > QUERY_SNIPPET_LENGTH)
                ? userQuery.substring(0, QUERY_SNIPPET_LENGTH)
                : "";
        if (userQuery != null && userQuery.length() <= QUERY_SNIPPET_LENGTH) {
            querySnippet = userQuery;
        }
        String responseSnippet = (assistantText != null && assistantText.length() > RESPONSE_SNIPPET_LENGTH)
                ? assistantText.substring(0, RESPONSE_SNIPPET_LENGTH)
                : "";
        if (assistantText != null && assistantText.length() <= RESPONSE_SNIPPET_LENGTH) {
            responseSnippet = assistantText;
        }

        String content = "Q: " + querySnippet + " → A: " + responseSnippet;
        if (content.length() > maxWorkflowContentLength) {
            content = content.substring(0, maxWorkflowContentLength);
        }

        MemoryEntry entry = MemoryEntry.of(MemoryType.WORKFLOW, sessionId, userId, content);
        memoryManager.remember(entry);
        log.debug("MemoryAdvisor: stored WORKFLOW memory entry ({} chars) for session={}",
                content.length(), sessionId);
    }

    /**
     * Extracts the first {@link UserMessage} text from the prompt, or {@code null} when absent.
     */
    private String extractUserQuery(final ChatClientRequest request) {
        Prompt prompt = request.prompt();
        return prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(msg -> ((UserMessage) msg).getText())
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts the {@code "userId"} entry from the request context map, or {@code null} when
     * absent or not a {@link String}.
     */
    private String extractUserId(final Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object raw = context.get("userId");
        return raw instanceof String s ? s : null;
    }

    /**
     * Extracts the assistant response text from a {@link ChatClientResponse}, or an empty
     * string when the response structure is incomplete.
     */
    private String extractAssistantText(final ChatClientResponse response) {
        if (response == null) {
            return "";
        }
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    // ─── Builder ──────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a {@link MemoryAdvisor} with customised settings.
     * Obtain an instance via {@link MemoryAdvisor#builder(AgentMemoryManager)}.
     */
    public static final class Builder {

        private final AgentMemoryManager memoryManager;
        private EntityMemoryStore entityMemoryStore;
        private PersonaStore personaStore;
        private int maxWorkflowContentLength = DEFAULT_MAX_WORKFLOW_CONTENT_LENGTH;
        private String injectPrefix = DEFAULT_INJECT_PREFIX;
        private String injectSuffix = DEFAULT_INJECT_SUFFIX;
        private int order = DEFAULT_ORDER;

        private Builder(final AgentMemoryManager memoryManager) {
            this.memoryManager = Objects.requireNonNull(memoryManager,
                    "AgentMemoryManager must not be null");
        }

        /**
         * Sets the optional entity memory store for structured entity fact recall.
         *
         * <p>When provided, facts observed in the current session are injected into the system
         * prompt as structured {@code [ENTITY FACTS]} blocks.
         *
         * @param entityMemoryStore the entity store; {@code null} disables entity injection
         * @return this builder
         */
        public Builder entityMemoryStore(final EntityMemoryStore entityMemoryStore) {
            this.entityMemoryStore = entityMemoryStore;
            return this;
        }

        /**
         * Sets the optional persona store for per-user preference and goal injection.
         *
         * <p>When provided, the user's persona fragment is prepended to the memory block,
         * personalising every response with the user's known preferences and goals.
         *
         * @param personaStore the persona store; {@code null} disables persona injection
         * @return this builder
         */
        public Builder personaStore(final PersonaStore personaStore) {
            this.personaStore = personaStore;
            return this;
        }

        /**
         * Sets the maximum total character length of the WORKFLOW memory entry stored after
         * each agent turn.
         *
         * <p>Content exceeding this length is truncated. Increase this value when long queries
         * or responses carry important workflow context; decrease it to keep the memory store
         * compact.
         *
         * @param maxWorkflowContentLength the character cap; values less than 1 are ignored
         *                                  (default: 500)
         * @return this builder
         */
        public Builder maxWorkflowContentLength(final int maxWorkflowContentLength) {
            if (maxWorkflowContentLength > 0) {
                this.maxWorkflowContentLength = maxWorkflowContentLength;
            }
            return this;
        }

        /**
         * Sets the prefix inserted before the memory block in the system prompt.
         *
         * @param injectPrefix the delimiter prefix; {@code null} restores the default
         *                     ({@code "\n\n[AGENT MEMORY]\n"})
         * @return this builder
         */
        public Builder injectPrefix(final String injectPrefix) {
            this.injectPrefix = injectPrefix;
            return this;
        }

        /**
         * Sets the suffix appended after the memory block in the system prompt.
         *
         * @param injectSuffix the delimiter suffix; {@code null} restores the default
         *                     ({@code "\n[END AGENT MEMORY]\n\n"})
         * @return this builder
         */
        public Builder injectSuffix(final String injectSuffix) {
            this.injectSuffix = injectSuffix;
            return this;
        }

        /**
         * Sets the position of this advisor in the {@link Ordered} advisor chain.
         * Lower values run earlier.
         *
         * @param order the chain order (default: {@code Ordered.HIGHEST_PRECEDENCE + 5})
         * @return this builder
         */
        public Builder order(final int order) {
            this.order = order;
            return this;
        }

        /**
         * Constructs the {@link MemoryAdvisor} from the current builder state.
         *
         * @return a fully configured {@link MemoryAdvisor}; never {@code null}
         */
        public MemoryAdvisor build() {
            return new MemoryAdvisor(this);
        }
    }
}
