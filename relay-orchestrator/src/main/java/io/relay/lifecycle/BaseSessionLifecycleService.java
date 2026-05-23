/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.relay.dto.BaseAuditTrailResponse;
import io.relay.dto.BaseBulkDeleteResponse;
import io.relay.dto.BaseDeleteSessionResponse;
import io.relay.dto.BaseResumeSessionResponse;
import io.relay.dto.BaseSessionStatusResponse;
import io.relay.model.BaseAgentAuditLog;
import io.relay.model.BaseAgentSession;
import io.relay.observability.AgentObservabilityService;
import io.relay.session.SessionStatus;
import io.relay.store.AgentAuditLogStore;
import io.relay.store.AgentSessionStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class that manages the full lifecycle of agent sessions, including creation,
 * retrieval, status transitions, deletion, bulk operations, resumption from checkpoints,
 * and audit-trail access.
 *
 * <p>All public operations execute synchronously on the calling virtual thread. Subclasses
 * supply the domain-specific mapping and cleanup hooks; the base class owns the orchestration,
 * validation, and persistence logic.
 *
 * <p>Completed sessions ({@code SessionStatus.COMPLETED}) are protected from deletion to
 * preserve the audit trail; callers receive an {@link IllegalArgumentException} with an
 * actionable message pointing to the retention policy.
 *
 * <h3>Extending this class</h3>
 * <p>Declare a Spring {@code @Service} that provides concrete types for {@code <S>} and
 * {@code <A>}, inject the required repositories via the Lombok-generated constructor, and
 * implement {@link #toSessionStatusResponse}. Override {@link #onSessionDelete} when
 * domain-specific rows must be cleaned up before the session row is removed.
 *
 * <pre>{@code
 * @Service
 * public class MySessionLifecycleService
 *         extends BaseSessionLifecycleService<MySessionDO, MyAuditLogDO> {
 *
 *     public MySessionLifecycleService(
 *             AgentSessionStore<MySessionDO> sessionStore,
 *             AgentAuditLogStore<MyAuditLogDO> auditLogStore,
 *             AgentObservabilityService observability) {
 *         super(sessionStore, auditLogStore, observability);
 *     }
 *
 *     @Override
 *     protected MySessionStatusResponse toSessionStatusResponse(MySessionDO session) {
 *         return MySessionStatusResponse.builder()
 *                 .sessionId(session.getSessionId())
 *                 .status(session.getStatus())
 *                 .myDomainField(session.getMyDomainField())
 *                 .build();
 *     }
 *
 *     @Override
 *     protected void onSessionDelete(String sessionId) {
 *         myDomainDataRepository.deleteBySessionId(sessionId);
 *     }
 * }
 * }</pre>
 *
 * @param <S> the session entity type, must extend {@link BaseAgentSession}
 * @param <A> the audit log entity type, must extend {@link BaseAgentAuditLog}
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseSessionLifecycleService<S extends BaseAgentSession, A extends BaseAgentAuditLog> {

    protected final AgentSessionStore<S> sessionStore;
    protected final AgentAuditLogStore<A> auditLogStore;
    protected final AgentObservabilityService observabilityService;

    // ─── Abstract methods for domain-specific logic ───────────────────────────

    /**
     * Converts a session entity into a status response DTO.
     *
     * <p>Implementations must populate at minimum the session ID and status. Additional
     * domain-specific fields (assigned agent, customer metadata, step details, etc.) should
     * be populated here so that all lifecycle endpoints return a consistent, enriched view.
     *
     * @param <R>     the concrete response type
     * @param session the session entity to convert; never null
     * @return the populated response DTO; never null
     */
    protected abstract <R extends BaseSessionStatusResponse> R toSessionStatusResponse(S session);

    /**
     * Hook invoked immediately before a session row is deleted, allowing subclasses to
     * remove domain-specific data that is not covered by cascade rules.
     *
     * <p>The default implementation is a no-op. Override when additional tables or
     * external resources must be cleaned up atomically with the session deletion.
     *
     * @param sessionId the ID of the session about to be deleted; never null
     */
    protected void onSessionDelete(final String sessionId) {
    }

    /**
     * Maps an audit log entity to the wire-format {@link BaseAuditTrailResponse.AuditEvent} DTO.
     *
     * <p>Override to include additional fields when the default mapping is insufficient
     * for domain-specific audit consumers.
     *
     * @param auditLog the audit log entity; never null
     * @return the populated audit event DTO; never null
     */
    protected BaseAuditTrailResponse.AuditEvent toAuditEvent(final A auditLog) {
        return BaseAuditTrailResponse.AuditEvent.builder()
                .id(auditLog.getId())
                .eventType(auditLog.getEventType())
                .toolName(auditLog.getToolName())
                .agentName(auditLog.getAgentName())
                .durationMs(auditLog.getDurationMs())
                .createdAt(auditLog.getCreatedAt() != null ? auditLog.getCreatedAt().toString() : null)
                .build();
    }

    /**
     * Performs an unchecked cast of a response object to the inferred return type.
     *
     * @param <R>      the target type
     * @param response the object to cast; may be null
     * @return the cast reference
     */
    @SuppressWarnings("unchecked")
    protected <R> R castResponse(final Object response) {
        return (R) response;
    }

    /**
     * Resolves a status string to the corresponding {@link SessionStatus} enum constant.
     *
     * @param status the status name (case-sensitive); never null
     * @return the matching {@link SessionStatus}
     * @throws IllegalStateException if the value does not match any known status
     */
    protected SessionStatus resolveSessionStatus(final String status) {
        try {
            return SessionStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Unknown session status: " + status, ex);
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns all sessions, optionally filtered by status, ordered from most-recently-updated
     * to least-recently-updated.
     *
     * @param <R>    the concrete response type produced by {@link #toSessionStatusResponse}
     * @param status if non-null and non-blank, only sessions in this status are returned;
     *               pass {@code null} or blank to retrieve all sessions
     * @return the list of session response DTOs; never null
     */
    public <R extends BaseSessionStatusResponse> List<R> listSessions(final String status) {
        List<S> sessions = (status != null && !status.isBlank())
                ? sessionStore.findByStatusOrderByUpdatedAtDesc(status)
                : sessionStore.findAllByOrderByUpdatedAtDesc();
        return sessions.stream()
                .map(this::<R>toSessionStatusResponse)
                .toList();
    }

    /**
     * Retrieves a single session by its unique identifier.
     *
     * @param <R>       the concrete response type produced by {@link #toSessionStatusResponse}
     * @param sessionId the session identifier; never null
     * @return the session response DTO
     * @throws IllegalArgumentException if no session exists with the given ID
     */
    public <R extends BaseSessionStatusResponse> R getSession(final String sessionId) {
        S session = sessionStore
                .findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        return this.toSessionStatusResponse(session);
    }

    /**
     * Deletes a session together with its audit logs and any domain-specific data
     * cleaned up by {@link #onSessionDelete}.
     *
     * <p>Completed sessions are not eligible for deletion and cause an
     * {@link IllegalArgumentException}. Use the platform's retention policy to archive
     * or purge completed sessions.
     *
     * @param <R>       the concrete delete-response type
     * @param sessionId the session to delete; never null
     * @return a response confirming deletion
     * @throws IllegalArgumentException if the session is not found or is in the
     *                                  {@code COMPLETED} state
     */
    public <R extends BaseDeleteSessionResponse> R deleteSession(final String sessionId) {
        S session = sessionStore
                .findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (SessionStatus.COMPLETED.name().equalsIgnoreCase(session.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot delete completed session: " + sessionId + ". Use retention policy.");
        }

        // Delete audit logs
        List<A> auditLogs = auditLogStore.findBySessionId(sessionId);
        if (!auditLogs.isEmpty()) {
            auditLogStore.deleteAll(auditLogs);
        }

        // Domain-specific cleanup
        onSessionDelete(sessionId);

        // Delete session
        sessionStore.delete(session);

        log.info("Deleted session {}", sessionId);

        return this.castResponse(BaseDeleteSessionResponse.builder()
                .sessionId(sessionId)
                .deleted(true)
                .message("Session deleted successfully")
                .build());
    }

    /**
     * Deletes multiple sessions in a single operation.
     *
     * <p>The operation is best-effort: sessions that are not found or are in the
     * {@code COMPLETED} state are skipped rather than causing the entire request to fail.
     * The response payload distinguishes deleted IDs, skipped-completed IDs, and
     * not-found IDs so callers can take appropriate follow-up action.
     *
     * @param <R>        the concrete bulk-delete response type
     * @param sessionIds the list of session IDs to delete; must not be null or empty
     * @return a response with per-ID outcome details
     * @throws IllegalArgumentException if {@code sessionIds} is null, empty, or contains
     *                                  only blank values
     */
    public <R extends BaseBulkDeleteResponse> R bulkDeleteSessions(final List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("sessionIds is required");
        }

        List<String> normalized = sessionIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("sessionIds is required");
        }

        List<S> found = sessionStore.findAllBySessionIdIn(normalized);
        Set<String> foundIds = found.stream()
                .map(BaseAgentSession::getSessionId)
                .collect(Collectors.toSet());

        List<String> notFound = normalized.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();

        List<String> skippedCompleted = found.stream()
                .filter(s -> SessionStatus.COMPLETED.name().equalsIgnoreCase(s.getStatus()))
                .map(BaseAgentSession::getSessionId)
                .toList();

        List<String> deletable = found.stream()
                .filter(s -> !SessionStatus.COMPLETED.name().equalsIgnoreCase(s.getStatus()))
                .map(BaseAgentSession::getSessionId)
                .toList();

        if (!deletable.isEmpty()) {
            // Delete audit logs
            List<A> logsToDelete = new ArrayList<>();
            for (String id : deletable) {
                logsToDelete.addAll(auditLogStore.findBySessionId(id));
            }
            if (!logsToDelete.isEmpty()) {
                auditLogStore.deleteAll(logsToDelete);
            }

            // Domain-specific cleanup
            deletable.forEach(this::onSessionDelete);

            // Delete sessions
            List<S> sessionsToDelete = found.stream()
                    .filter(s -> deletable.contains(s.getSessionId()))
                    .toList();
            sessionStore.deleteAll(new ArrayList<>(sessionsToDelete));

            log.info("Bulk deleted {} sessions", deletable.size());
        }

        return this.castResponse(BaseBulkDeleteResponse.builder()
                .requested(normalized.size())
                .deleted(deletable.size())
                .deletedSessionIds(deletable)
                .skippedCompletedSessionIds(skippedCompleted)
                .notFoundSessionIds(notFound)
                .build());
    }

    /**
     * Resumes a paused or failed session, transitioning it back to the {@code ACTIVE} state
     * and restoring execution from the last recorded checkpoint.
     *
     * <p>If the session is already {@code ACTIVE} the operation succeeds immediately
     * without modifying the session. Sessions in any other terminal state except
     * {@code FAILED} cannot be resumed and cause an {@link IllegalStateException}.
     *
     * @param <R>       the concrete resume-response type
     * @param sessionId the session to resume; never null
     * @return a response that includes the checkpoint the agent will resume from
     * @throws IllegalArgumentException if the session is not found
     * @throws IllegalStateException    if the session is in a terminal state other than
     *                                  {@code FAILED}
     */
    public <R extends BaseResumeSessionResponse> R resumeSession(final String sessionId) {
        S session = sessionStore
                .findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (SessionStatus.ACTIVE.name().equals(session.getStatus())) {
            return this.castResponse(BaseResumeSessionResponse.builder()
                    .sessionId(sessionId)
                    .resumedFromCheckpoint(session.getLastCheckpoint())
                    .status(session.getStatus())
                    .message("Session is already active.")
                    .build());
        }

        SessionStatus currentStatus = resolveSessionStatus(session.getStatus());
        if (!currentStatus.canResume()) {
            throw new IllegalStateException(
                    "Cannot resume session in state: " + session.getStatus());
        }

        String lastCheckpoint = session.getLastCheckpoint();
        session.setStatus(SessionStatus.ACTIVE.name());
        sessionStore.save(session);
        observabilityService.recordSessionEvent("resumed");

        String message = lastCheckpoint != null
                ? "Resumed from " + lastCheckpoint + ". Completed steps will be skipped."
                : "Resumed from beginning. No checkpoint found.";

        log.info("Resumed session {} from checkpoint {}", sessionId, lastCheckpoint);

        return this.castResponse(BaseResumeSessionResponse.builder()
                .sessionId(sessionId)
                .resumedFromCheckpoint(lastCheckpoint)
                .status(SessionStatus.ACTIVE.name())
                .message(message)
                .build());
    }

    /**
     * Returns the chronological audit trail for a session, optionally filtered by event type.
     *
     * <p>Each entry in the trail records a discrete agent event such as a tool call,
     * LLM invocation, or status transition, including its duration in milliseconds.
     *
     * @param <R>       the concrete audit-trail response type
     * @param sessionId the session whose audit log is requested; never null
     * @param eventType if non-null and non-blank, only events matching this type are returned;
     *                  pass {@code null} or blank to retrieve all events
     * @return the full (or filtered) audit trail; the event list is empty rather than null
     *         when no matching events exist
     */
    public <R extends BaseAuditTrailResponse> R getAuditTrail(final String sessionId, final String eventType) {
        List<A> logs;
        if (eventType != null && !eventType.isBlank()) {
            logs = auditLogStore.findBySessionIdAndEventType(sessionId, eventType);
        } else {
            logs = auditLogStore.findBySessionId(sessionId);
        }

        List<BaseAuditTrailResponse.AuditEvent> events = logs.stream()
                .map(this::toAuditEvent)
                .toList();

        return this.castResponse(BaseAuditTrailResponse.builder()
                .sessionId(sessionId)
                .events(events)
                .totalEvents(events.size())
                .build());
    }
}
