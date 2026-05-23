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
package io.agentcore.checkpoint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.repository.BaseAgentSessionRepository;
import io.agentcore.session.SessionContextManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base manager for chat turn persistence and conversation history.
 * <p>
 * Appends user/assistant messages to the session's contextJson.
 * Domain-specific agents should extend this class to add their own
 * message processing (e.g., extracting entities from user messages).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Component
 * public class OnboardingChatHistoryManager
 *         extends BaseChatHistoryManager<AgentSessionDO> {
 *
 *     @Override
 *     protected void processUserMessage(AgentSessionDO session, Map<String, Object> context, String content) {
 *         // Extract market/banner from user message
 *         messageParser.applyCanonicalOverridesFromUserMessage(session, context, content);
 *     }
 * }
 * }</pre>
 *
 * <p>Stateless; safe for concurrent use.
 *
 * @param <S> the session entity type extending BaseAgentSession
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseChatHistoryManager<S extends BaseAgentSession> {

    protected final SessionContextManager sessionContextManager;
    protected final ObjectMapper objectMapper;

    /**
     * Returns the session repository for the concrete session type.
     */
    protected abstract BaseAgentSessionRepository<S> getSessionRepository();

    /**
     * Hook for processing user messages before persistence.
     * <p>
     * Subclasses can override to extract entities, update canonical fields,
     * or modify the session/context based on message content.
     *
     * @param session the session entity (can be mutated)
     * @param context the context map (can be mutated)
     * @param content the user message content
     */
    protected void processUserMessage(final S session, final Map<String, Object> context, final String content) {
        // Default: no-op, subclasses can override
    }

    /**
     * Hook for processing assistant messages before persistence.
     *
     * @param session the session entity (can be mutated)
     * @param context the context map (can be mutated)
     * @param content the assistant message content
     */
    protected void processAssistantMessage(final S session, final Map<String, Object> context, final String content) {
        // Default: no-op, subclasses can override
    }

    /**
     * Hook called after chat turns are updated.
     * Subclasses can override to trigger LLM summarization.
     *
     * @param session the session entity
     * @param context the updated context
     * @param content the message content
     */
    protected void afterChatTurnAppended(final S session, final Map<String, Object> context, final String content) {
        // Default: no-op, subclasses can override for LLM summarization
    }

    /**
     * Appends a chat turn (user or assistant message) to the session history.
     * <p>
     * For user messages, also calls {@link #processUserMessage} for custom processing.
     *
     * @param session the session to update (will be mutated with fresh contextJson)
     * @param role    "user" or "assistant"
     * @param content message content
     */
    public void appendChatTurn(final S session, final String role, final String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        S sessionToUpdate = getSessionRepository()
                .findBySessionId(session.getSessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Session not found while appending chat turn: " + session.getSessionId()));

        Map<String, Object> context = sessionContextManager.parseContextJson(sessionToUpdate.getContextJson());

        if ("user".equalsIgnoreCase(role)) {
            processUserMessage(sessionToUpdate, context, content);
        } else if ("assistant".equalsIgnoreCase(role)) {
            processAssistantMessage(sessionToUpdate, context, content);
        }

        List<Map<String, Object>> turns = getOrCreateChatTurns(context);
        turns.add(createTurnEntry(role, content));

        afterChatTurnAppended(sessionToUpdate, context, content);

        try {
            sessionToUpdate.setContextJson(objectMapper.writeValueAsString(context));
            getSessionRepository().save(sessionToUpdate);

            // Sync back to caller's session object
            syncSessionState(session, sessionToUpdate);
        } catch (JsonProcessingException e) {
            log.error("Failed to persist chat turn for session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    /**
     * Gets the chat turns from a session's context.
     *
     * @param sessionId the session identifier
     * @return list of chat turns (each turn is a map with role, content, createdAt)
     */
    public List<Map<String, Object>> getChatTurns(final String sessionId) {
        return getSessionRepository().findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
                    return getOrCreateChatTurns(context);
                })
                .orElse(List.of());
    }

    /**
     * Clears all chat turns from a session (useful for session reset).
     *
     * @param sessionId the session identifier
     */
    public void clearChatHistory(final String sessionId) {
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            context.put(SessionContextManager.CONTEXT_KEY_CHAT_TURNS, new ArrayList<>());
            try {
                session.setContextJson(objectMapper.writeValueAsString(context));
                getSessionRepository().save(session);
                log.info("Chat history cleared for session {}", sessionId);
            } catch (JsonProcessingException e) {
                log.error("Failed to clear chat history for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * Syncs state from the updated session back to the caller's session object.
     * Subclasses can override to sync additional domain-specific fields.
     *
     * @param target the caller's session object to update
     * @param source the freshly saved session object
     */
    protected void syncSessionState(final S target, final S source) {
        target.setContextJson(source.getContextJson());
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> getOrCreateChatTurns(final Map<String, Object> context) {
        Object turnsObj = context.get(SessionContextManager.CONTEXT_KEY_CHAT_TURNS);
        if (turnsObj instanceof List<?>) {
            return (List<Map<String, Object>>) turnsObj;
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        context.put(SessionContextManager.CONTEXT_KEY_CHAT_TURNS, turns);
        return turns;
    }

    protected Map<String, Object> createTurnEntry(final String role, final String content) {
        Map<String, Object> turn = new HashMap<>();
        turn.put("role", role);
        turn.put("content", content);
        turn.put("createdAt", LocalDateTime.now().toString());
        return turn;
    }
}
