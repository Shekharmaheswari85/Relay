/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.checkpoint;

import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;
import io.relay.session.SessionContextManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base manager for session checkpoints and step progression.
 * <p>
 * Provides generic checkpoint persistence, step transition validation, and
 * working state summary building. Domain-specific agents should extend this
 * class and implement the abstract methods for their workflow steps.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Component
 * public class OnboardingCheckpointManager
 *         extends BaseCheckpointManager<AgentSessionDO> {
 *
 *     @Override
 *     protected boolean isValidStepTransition(String from, String to) {
 *         StepFlow current = StepFlow.valueOf(from);
 *         StepFlow next = StepFlow.valueOf(to);
 *         return next.isAfter(current);
 *     }
 *
 *     @Override
 *     protected String buildWorkingStateSummary(AgentSessionDO session, Map<String, Object> context) {
 *         // Build domain-specific summary
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
public abstract class BaseCheckpointManager<S extends BaseAgentSession> {

    public static final String CONTEXT_KEY_WORKING_STATE_SUMMARY = "workingStateSummary";

    protected final SessionContextManager sessionContextManager;
    protected final ObjectMapper objectMapper;

    /**
     * Returns the session repository for the concrete session type.
     * Subclasses must provide their repository instance.
     */
    protected abstract BaseAgentSessionRepository<S> getSessionRepository();

    /**
     * Validates that a step transition is allowed.
     * Default implementation allows all forward transitions and warns on backward jumps.
     *
     * @param fromStep the current step
     * @param toStep   the proposed next step
     * @return true if the transition is valid (forward), false for backward jumps
     */
    protected boolean isValidStepTransition(final String fromStep, final String toStep) {
        // Default: allow all transitions, subclasses can override for strict validation
        return true;
    }

    /**
     * Builds a human-readable summary of the session's working state.
     * Default implementation returns basic step and checkpoint info.
     *
     * @param session the session entity
     * @param context the parsed context map
     * @return summary string
     */
    protected String buildWorkingStateSummary(final S session, final Map<String, Object> context) {
        StringBuilder summary = new StringBuilder();
        summary.append("- currentStep: ")
                .append(sessionContextManager.asString(session.getCurrentStep(), "INIT"))
                .append('\n');
        summary.append("- lastCheckpoint: ")
                .append(sessionContextManager.asString(session.getLastCheckpoint(), "(none)"))
                .append('\n');
        summary.append("- status: ")
                .append(sessionContextManager.asString(session.getStatus(), "UNKNOWN"))
                .append('\n');
        return summary.toString().trim();
    }

    /**
     * Hook called after checkpoint is saved.
     * Subclasses can override to emit events, update status snapshots, etc.
     *
     * @param session       the updated session
     * @param step          the checkpoint step
     * @param context       the updated context
     * @param contextUpdate the context fields that were updated
     */
    protected void onCheckpointSaved(
            final S session,
            final String step,
            final Map<String, Object> context,
            final Map<String, Object> contextUpdate) {
        // Default: no-op, subclasses can override
    }

    /**
     * Hook called before working state summary is updated.
     * Subclasses can override to trigger LLM summarization.
     *
     * @param session the session entity
     * @param context the context map
     */
    protected void beforeWorkingStateSummaryUpdate(final S session, final Map<String, Object> context) {
        // Default: no-op, subclasses can override for LLM summarization
    }

    // ─── Checkpoint persistence ─────────────────────────────────────────────

    /**
     * Persists a step checkpoint.
     * <p>
     * Validates the step transition before saving — a warning is emitted for backward jumps
     * but the checkpoint is persisted regardless to support retry and recovery paths.
     *
     * @param sessionId     the session identifier
     * @param step          the checkpoint step name
     * @param contextUpdate map of context fields to merge
     */
    public void saveCheckpoint(final String sessionId, final String step, final Map<String, Object> contextUpdate) {
        findSession(sessionId).ifPresent(session -> {
            validateTransition(sessionId, session.getCurrentStep(), step);
            session.setCurrentStep(step);
            session.setLastCheckpoint(step);

            Map<String, Object> existingContext = sessionContextManager.parseContextJson(session.getContextJson());
            existingContext.putAll(contextUpdate);

            beforeWorkingStateSummaryUpdate(session, existingContext);
            existingContext.put(CONTEXT_KEY_WORKING_STATE_SUMMARY, buildWorkingStateSummary(session, existingContext));

            try {
                session.setContextJson(objectMapper.writeValueAsString(existingContext));
            } catch (JacksonException e) {
                log.error("Failed to serialize context for session {}: {}", sessionId, e.getMessage());
                return;
            }

            getSessionRepository().save(session);
            onCheckpointSaved(session, step, existingContext, contextUpdate);
            log.info("Checkpoint saved: session={}, step={}", sessionId, step);
        });
    }

    /**
     * Refreshes the working state summary without changing the checkpoint step.
     *
     * @param sessionId the session identifier
     */
    public void refreshWorkingStateSummary(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        findSession(sessionId).ifPresent(session -> {
            Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
            beforeWorkingStateSummaryUpdate(session, context);
            context.put(CONTEXT_KEY_WORKING_STATE_SUMMARY, buildWorkingStateSummary(session, context));
            try {
                session.setContextJson(objectMapper.writeValueAsString(context));
                getSessionRepository().save(session);
            } catch (JacksonException ex) {
                log.warn("Failed to refresh working state summary for session {}: {}", sessionId, ex.getMessage());
            }
        });
    }

    /**
     * Updates the session step without creating a full checkpoint.
     *
     * @param sessionId the session identifier
     * @param step      the new step
     */
    public void updateStep(final String sessionId, final String step) {
        findSession(sessionId).ifPresent(session -> {
            validateTransition(sessionId, session.getCurrentStep(), step);
            session.setCurrentStep(step);
            getSessionRepository().save(session);
            log.debug("Step updated: session={}, step={}", sessionId, step);
        });
    }

    /**
     * Gets the current checkpoint step for a session.
     *
     * @param sessionId the session identifier
     * @return the checkpoint step, or empty if session not found
     */
    public Optional<String> getCurrentCheckpoint(final String sessionId) {
        return findSession(sessionId).map(BaseAgentSession::getLastCheckpoint);
    }

    /**
     * Gets the current step for a session.
     *
     * @param sessionId the session identifier
     * @return the current step, or empty if session not found
     */
    public Optional<String> getCurrentStep(final String sessionId) {
        return findSession(sessionId).map(BaseAgentSession::getCurrentStep);
    }

    // ─── Step transition validation ─────────────────────────────────────────

    /**
     * Validates that {@code nextStep} is a valid transition from {@code currentStep}.
     * Backward transitions are allowed (to support retry and recovery paths) but produce a
     * {@code WARN} log with the tag {@code INVALID_STEP_TRANSITION} for observability.
     */
    protected void validateTransition(final String sessionId, final String currentStep, final String nextStep) {
        if (currentStep == null || currentStep.isBlank()) {
            return;
        }
        try {
            if (!isValidStepTransition(currentStep, nextStep)) {
                log.warn(
                        "INVALID_STEP_TRANSITION session={} current={} proposed={} — backward jump detected; saving anyway",
                        sessionId,
                        currentStep,
                        nextStep);
            }
        } catch (IllegalArgumentException ex) {
            log.warn("UNKNOWN_STEP_NAME session={} current={} proposed={}", sessionId, currentStep, nextStep);
        }
    }

    protected Optional<S> findSession(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return getSessionRepository().findBySessionId(sessionId);
    }
}
