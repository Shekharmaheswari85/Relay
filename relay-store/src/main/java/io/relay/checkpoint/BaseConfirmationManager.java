/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.checkpoint;

import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;
import io.relay.session.SessionContextManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base manager for user confirmation state in mutation tool workflows.
 * <p>
 * Persists confirmation signals into contextJson before LLM calls so they survive
 * session crashes between "user says yes" and "mutation tool executes".
 * <p>
 * Domain-specific agents should extend this class and implement
 * {@link #resolveExpectedMutationTool} to map workflow steps to mutation tools.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Component
 * public class OnboardingConfirmationManager
 *         extends BaseConfirmationManager<AgentSessionDO> {
 *
 *     @Override
 *     protected String resolveExpectedMutationTool(String currentStep) {
 *         return switch (StepFlow.valueOf(currentStep)) {
 *             case PLAN_APPROVED -> "registerMarket";
 *             case STEP_2_APPLY -> "enqueueToPending";
 *             default -> null;
 *         };
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
public abstract class BaseConfirmationManager<S extends BaseAgentSession> {

    public static final String CONTEXT_KEY_USER_CONFIRMED = "userConfirmed";
    public static final String CONTEXT_KEY_PENDING_MUTATION_TOOL = "pendingMutationTool";

    protected final SessionContextManager sessionContextManager;
    protected final ObjectMapper objectMapper;

    /**
     * Returns the session repository for the concrete session type.
     */
    protected abstract BaseAgentSessionRepository<S> getSessionRepository();

    /**
     * Maps the current workflow step to the mutation tool most likely to be called next.
     * <p>
     * Subclasses must implement this to provide domain-specific step-to-tool mapping.
     *
     * @param currentStep the current workflow step
     * @return the expected mutation tool name, or null if no mutation is expected
     */
    protected abstract String resolveExpectedMutationTool(String currentStep);

    /**
     * Persists user confirmation into contextJson before the LLM call.
     * This ensures the signal survives a session crash.
     *
     * @param session the session entity (will be updated)
     * @param context the context map (will be updated)
     */
    public void persistConfirmation(final S session, final Map<String, Object> context) {
        String expectedTool = resolveExpectedMutationTool(session.getCurrentStep());
        context.put(CONTEXT_KEY_USER_CONFIRMED, true);
        if (expectedTool != null) {
            context.put(CONTEXT_KEY_PENDING_MUTATION_TOOL, expectedTool);
        }
        try {
            session.setContextJson(objectMapper.writeValueAsString(context));
            getSessionRepository().save(session);
            log.info(
                    "Persisted user confirmation for session {} (step={}, pendingTool={})",
                    session.getSessionId(),
                    session.getCurrentStep(),
                    expectedTool);
        } catch (JacksonException e) {
            log.warn("Failed to persist confirmation for session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    /**
     * Clears persisted confirmation state after a mutation tool succeeds.
     * Called by tool execution aspects on mutation tool success.
     *
     * @param sessionId the session identifier
     */
    public void clearPendingConfirmation(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        getSessionRepository().findBySessionId(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            boolean hadConfirmation = context.containsKey(CONTEXT_KEY_USER_CONFIRMED)
                    || context.containsKey(CONTEXT_KEY_PENDING_MUTATION_TOOL);
            context.remove(CONTEXT_KEY_USER_CONFIRMED);
            context.remove(CONTEXT_KEY_PENDING_MUTATION_TOOL);
            if (hadConfirmation) {
                try {
                    session.setContextJson(objectMapper.writeValueAsString(context));
                    getSessionRepository().save(session);
                    log.debug("Cleared pending confirmation for session {}", sessionId);
                } catch (JacksonException e) {
                    log.warn("Failed to clear confirmation for session {}: {}", sessionId, e.getMessage());
                }
            }
        });
    }

    /**
     * Checks if the session has a pending user confirmation.
     *
     * @param sessionId the session identifier
     * @return true if user has confirmed and mutation is pending
     */
    public boolean hasPendingConfirmation(final String sessionId) {
        return getSessionRepository().findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
                    return Boolean.TRUE.equals(context.get(CONTEXT_KEY_USER_CONFIRMED));
                })
                .orElse(false);
    }

    /**
     * Gets the pending mutation tool name for a session.
     *
     * @param sessionId the session identifier
     * @return the pending tool name, or null if none
     */
    public String getPendingMutationTool(final String sessionId) {
        return getSessionRepository().findBySessionId(sessionId)
                .map(session -> {
                    Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
                    Object tool = context.get(CONTEXT_KEY_PENDING_MUTATION_TOOL);
                    return tool instanceof String str ? str : null;
                })
                .orElse(null);
    }
}
