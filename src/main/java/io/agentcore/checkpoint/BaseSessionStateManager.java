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

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.repository.BaseAgentSessionRepository;
import io.agentcore.session.SessionContextManager;
import io.agentcore.session.SessionStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base manager for session state mutations (failure marking, tool call tracking).
 * <p>
 * Domain-specific agents can extend this class to add custom state mutation logic.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Component
 * public class OnboardingSessionStateManager
 *         extends BaseSessionStateManager<AgentSessionDO> {
 *
 *     @Override
 *     protected void onSessionFailed(AgentSessionDO session, String reason) {
 *         observabilityService.recordSessionEvent("failed");
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
public abstract class BaseSessionStateManager<S extends BaseAgentSession> {

    public static final String CONTEXT_KEY_FAILURE_REASON = "failureReason";

    protected final SessionContextManager sessionContextManager;
    protected final ObjectMapper objectMapper;

    /**
     * Returns the session repository for the concrete session type.
     */
    protected abstract BaseAgentSessionRepository<S> getSessionRepository();

    /**
     * Hook called after a session is marked as failed.
     * Subclasses can override to emit metrics, evict caches, etc.
     *
     * @param session the failed session
     * @param reason  the failure reason
     */
    protected void onSessionFailed(final S session, final String reason) {
        // Default: no-op, subclasses can override
    }

    /**
     * Hook called after a session is marked as completed.
     *
     * @param session the completed session
     */
    protected void onSessionCompleted(final S session) {
        // Default: no-op, subclasses can override
    }

    /**
     * Hook called after a tool call is recorded.
     *
     * @param session        the session
     * @param totalToolCalls the new total tool call count
     */
    protected void onToolCallRecorded(final S session, final int totalToolCalls) {
        // Default: no-op, subclasses can override
    }

    /**
     * Marks a session as failed and records the failure reason.
     *
     * @param session the session to mark as failed
     * @param reason  the failure reason
     */
    public void markSessionFailed(final S session, final String reason) {
        try {
            session.setStatus(SessionStatus.FAILED.name());
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            context.put(CONTEXT_KEY_FAILURE_REASON, reason);
            session.setContextJson(objectMapper.writeValueAsString(context));
            getSessionRepository().save(session);
            onSessionFailed(session, reason);
            log.info("Session {} marked as FAILED: {}", session.getSessionId(), reason);
        } catch (Exception e) {
            log.error("Failed to mark session {} as failed: {}", session.getSessionId(), e.getMessage());
        }
    }

    /**
     * Marks a session as failed by session ID.
     *
     * @param sessionId the session identifier
     * @param reason    the failure reason
     */
    public void markSessionFailed(final String sessionId, final String reason) {
        getSessionRepository().findBySessionId(sessionId)
                .ifPresent(session -> markSessionFailed(session, reason));
    }

    /**
     * Marks a session as completed.
     *
     * @param session the session to mark as completed
     */
    public void markSessionCompleted(final S session) {
        session.setStatus(SessionStatus.COMPLETED.name());
        getSessionRepository().save(session);
        onSessionCompleted(session);
        log.info("Session {} marked as COMPLETED", session.getSessionId());
    }

    /**
     * Marks a session as completed by session ID.
     *
     * @param sessionId the session identifier
     */
    public void markSessionCompleted(final String sessionId) {
        getSessionRepository().findBySessionId(sessionId)
                .ifPresent(this::markSessionCompleted);
    }

    /**
     * Marks a session as paused.
     *
     * @param sessionId the session identifier
     */
    public void markSessionPaused(final String sessionId) {
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            session.setStatus(SessionStatus.PAUSED.name());
            getSessionRepository().save(session);
            log.info("Session {} marked as PAUSED", sessionId);
        });
    }

    /**
     * Resumes a paused session (sets status back to ACTIVE).
     *
     * @param sessionId the session identifier
     */
    public void resumeSession(final String sessionId) {
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            if (SessionStatus.PAUSED.name().equals(session.getStatus())) {
                session.setStatus(SessionStatus.ACTIVE.name());
                getSessionRepository().save(session);
                log.info("Session {} resumed (PAUSED -> ACTIVE)", sessionId);
            }
        });
    }

    /**
     * Increments {@code totalToolCalls} in the session context.
     * Tool implementations should call this after each successful tool execution.
     *
     * @param sessionId the session identifier
     */
    public void recordToolCall(final String sessionId) {
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            sessionContextManager.incrementToolCalls(context);
            int totalToolCalls = sessionContextManager.asInt(
                    context.get(SessionContextManager.CONTEXT_KEY_TOTAL_TOOL_CALLS), 0);
            try {
                session.setContextJson(objectMapper.writeValueAsString(context));
                getSessionRepository().save(session);
                onToolCallRecorded(session, totalToolCalls);
                log.debug("Tool call recorded: session={}, totalToolCalls={}", sessionId, totalToolCalls);
            } catch (JsonProcessingException e) {
                log.error("Failed to record tool call for session {}: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * Gets the current tool call count for a session.
     *
     * @param sessionId the session identifier
     * @return the total tool call count
     */
    public int getToolCallCount(final String sessionId) {
        return getSessionRepository().findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
                    return sessionContextManager.asInt(
                            context.get(SessionContextManager.CONTEXT_KEY_TOTAL_TOOL_CALLS), 0);
                })
                .orElse(0);
    }

    /**
     * Updates the session status.
     *
     * @param sessionId the session identifier
     * @param status    the new status
     */
    public void updateStatus(final String sessionId, final SessionStatus status) {
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            session.setStatus(status.name());
            getSessionRepository().save(session);
            log.debug("Session {} status updated to {}", sessionId, status);
        });
    }
}
