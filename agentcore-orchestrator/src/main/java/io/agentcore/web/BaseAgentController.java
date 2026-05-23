/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.agentcore.advisor.ConfirmationGateAdvisor;
import io.agentcore.dto.BaseAuditTrailResponse;
import io.agentcore.dto.BaseBulkDeleteResponse;
import io.agentcore.dto.BaseCreateSessionRequest;
import io.agentcore.dto.BaseCreateSessionResponse;
import io.agentcore.dto.BaseDeleteSessionResponse;
import io.agentcore.dto.BaseResumeSessionResponse;
import io.agentcore.dto.BaseSessionStatusResponse;
import io.agentcore.dto.ConfirmMutationRequest;
import io.agentcore.dto.SendMessageRequestDTO;
import io.agentcore.executor.BaseAgentRuntimeService;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.session.TenantResolver;
import io.agentcore.stream.PipelineEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base REST controller for agent sessions.
 *
 * <p>Provides standard endpoints for session lifecycle management and message streaming.
 * Teams extend this class and annotate with {@code @RestController} and
 * {@code @RequestMapping} to expose their agent over HTTP.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/my-agent")
 * public class MyAgentController extends BaseAgentController<MySessionDO, MyCreateSessionRequest> {
 *
 *     public MyAgentController(MyAgentRuntimeService runtimeService,
 *                              ObjectProvider<TenantResolver> tenantResolver) {
 *         super(runtimeService, tenantResolver.getIfAvailable());
 *     }
 * }
 * }</pre>
 *
 * <h3>Endpoints provided</h3>
 * <table>
 *   <tr><td>{@code POST /sessions}</td><td>Create a new session</td></tr>
 *   <tr><td>{@code POST /sessions/{sessionId}/messages}</td><td>Send a message (SSE stream)</td></tr>
 *   <tr><td>{@code GET /sessions}</td><td>List all sessions</td></tr>
 *   <tr><td>{@code GET /sessions/{sessionId}}</td><td>Get session status</td></tr>
 *   <tr><td>{@code DELETE /sessions/{sessionId}}</td><td>Delete a session</td></tr>
 *   <tr><td>{@code DELETE /sessions}</td><td>Bulk delete sessions</td></tr>
 *   <tr><td>{@code POST /sessions/{sessionId}/resume}</td><td>Resume a session</td></tr>
 *   <tr><td>{@code GET /sessions/{sessionId}/audit}</td><td>Get audit trail</td></tr>
 * </table>
 *
 * @param <S>   the session entity type
 * @param <REQ> the create-session request type
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseAgentController<S extends BaseAgentSession, REQ extends BaseCreateSessionRequest> {

    /**
     * Default SSE timeout: 10 minutes. Override {@link #sseTimeoutMillis()} to change.
     */
    protected static final long DEFAULT_SSE_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    protected final BaseAgentRuntimeService<S> runtimeService;
    protected final TenantResolver tenantResolver;

    // ─── Session creation ─────────────────────────────────────────────────────

    /**
     * Creates a new agent session.
     *
     * @param <RES>    the concrete response type
     * @param request  the create-session request body
     * @param httpReq  the current HTTP request (used for tenant resolution)
     * @return the created session response
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public <RES extends BaseCreateSessionResponse> RES createSession(
            @RequestBody final REQ request,
            final HttpServletRequest httpReq) {

        enrichWithTenant(request, httpReq);
        log.info("Creating session: agentId={} createdBy={} tenantId={}",
                request.getAgentId(), request.getCreatedBy(), request.getTenantId());
        return runtimeService.createSession(request);
    }

    // ─── Message streaming ────────────────────────────────────────────────────

    /**
     * Sends a message to an existing session and streams the response via SSE.
     *
     * <p>The method opens a {@link SseEmitter} with the configured timeout, submits the agent
     * pipeline to a virtual thread, and returns the emitter to the servlet container. All SSE
     * events — {@code stage}, {@code message}, {@code thinking}, {@code tool_progress},
     * {@code follow_up_questions}, {@code confirmation_required}, and {@code done} — are written
     * directly to the emitter from the virtual thread.
     *
     * @param sessionId the session identifier
     * @param request   the message request body
     * @return an {@link SseEmitter} that delivers the agent response stream
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable final String sessionId,
            @RequestBody final SendMessageRequestDTO request) {

        log.info("Message received: sessionId={}", sessionId);

        SseEmitter emitter = new SseEmitter(sseTimeoutMillis());
        PipelineEmitter pipelineEmitter = new PipelineEmitter(emitter, sessionId);

        Thread.ofVirtual().name("agent-sse-" + sessionId).start(() -> {
            try {
                runtimeService.sendMessage(sessionId, request.getContent(), pipelineEmitter);
            } catch (Exception ex) {
                log.error("Unhandled error in agent pipeline for session {}: {}", sessionId, ex.getMessage(), ex);
                pipelineEmitter.sendError("Internal error: " + ex.getMessage());
                pipelineEmitter.complete();
            }
        });

        return emitter;
    }

    // ─── Confirmation gate ────────────────────────────────────────────────────

    /**
     * Submits a user's confirmation or rejection decision for a pending mutation tool call
     * and streams the agent's continuation response via SSE.
     *
     * <h3>Protocol</h3>
     * <ol>
     *   <li>The agent pipeline returns a {@code confirmation_required} SSE event containing
     *       the pending tool name when {@code ConfirmationGateAdvisor} intercepts a MUTATION
     *       tool call without existing user approval.</li>
     *   <li>The UI displays a confirmation prompt to the user.</li>
     *   <li>The user's decision is posted here. When {@code confirmed=true}, the advisor
     *       enriches the context with {@code user_confirmed=true} and lets the pipeline
     *       proceed. When {@code confirmed=false}, the advisor returns a cancellation
     *       response immediately.</li>
     * </ol>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * POST /api/my-agent/sessions/sess-abc123/confirm
     * Content-Type: application/json
     * Accept: text/event-stream
     *
     * { "toolName": "deleteUser", "confirmed": true }
     * }</pre>
     *
     * @param sessionId the session awaiting confirmation
     * @param request   the confirmation decision from the user
     * @return an {@link SseEmitter} that delivers the continuation stream
     */
    @PostMapping(value = "/sessions/{sessionId}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmMutation(
            @PathVariable final String sessionId,
            @RequestBody final ConfirmMutationRequest request) {

        log.info("Mutation confirmation: sessionId={} toolName={} confirmed={}",
                sessionId, request.getToolName(), request.isConfirmed());

        SseEmitter emitter = new SseEmitter(sseTimeoutMillis());
        PipelineEmitter pipelineEmitter = new PipelineEmitter(emitter, sessionId);

        String content = request.isConfirmed()
                ? ConfirmationGateAdvisor.CONFIRM_PREFIX + request.getToolName()
                : ConfirmationGateAdvisor.REJECT_PREFIX + request.getToolName();

        Thread.ofVirtual().name("agent-confirm-" + sessionId).start(() -> {
            try {
                runtimeService.sendMessage(sessionId, content, pipelineEmitter);
            } catch (Exception ex) {
                log.error("Error processing confirmation for session {}: {}", sessionId, ex.getMessage(), ex);
                pipelineEmitter.sendError("Confirmation error: " + ex.getMessage());
                pipelineEmitter.complete();
            }
        });

        return emitter;
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Lists sessions, optionally filtered by status.
     *
     * @param <STATUS> the concrete status response type
     * @param status   optional status filter (e.g., "ACTIVE", "COMPLETED")
     * @return list of session status responses
     */
    @GetMapping("/sessions")
    public <STATUS extends BaseSessionStatusResponse> List<STATUS> listSessions(
            @RequestParam(required = false) final String status) {
        return runtimeService.listSessions(status);
    }

    /**
     * Returns the status and details of a specific session.
     *
     * @param <STATUS>  the concrete status response type
     * @param sessionId the session identifier
     * @return session status response
     */
    @GetMapping("/sessions/{sessionId}")
    public <STATUS extends BaseSessionStatusResponse> STATUS getSession(
            @PathVariable final String sessionId) {
        return runtimeService.getSession(sessionId);
    }

    /**
     * Deletes a session and its associated data.
     *
     * @param <DEL>     the concrete deletion response type
     * @param sessionId the session identifier
     * @return delete confirmation response
     */
    @DeleteMapping("/sessions/{sessionId}")
    public <DEL extends BaseDeleteSessionResponse> DEL deleteSession(
            @PathVariable final String sessionId) {
        log.info("Deleting session: sessionId={}", sessionId);
        return runtimeService.deleteSession(sessionId);
    }

    /**
     * Bulk-deletes multiple sessions.
     *
     * @param <BULK>      the concrete bulk-delete response type
     * @param sessionIds  list of session IDs to delete
     * @return bulk delete result response
     */
    @DeleteMapping("/sessions")
    public <BULK extends BaseBulkDeleteResponse> BULK bulkDeleteSessions(
            @RequestBody final List<String> sessionIds) {
        log.info("Bulk deleting {} sessions", sessionIds.size());
        return runtimeService.bulkDeleteSessions(sessionIds);
    }

    /**
     * Resumes a paused or failed session.
     *
     * @param <RESUME>  the concrete resume response type
     * @param sessionId the session identifier
     * @return resume response
     */
    @PostMapping("/sessions/{sessionId}/resume")
    public <RESUME extends BaseResumeSessionResponse> RESUME resumeSession(
            @PathVariable final String sessionId) {
        log.info("Resuming session: sessionId={}", sessionId);
        return runtimeService.resumeSession(sessionId);
    }

    // ─── Audit ────────────────────────────────────────────────────────────────

    /**
     * Returns the audit trail for a session, optionally filtered by event type.
     *
     * @param <AUDIT>   the concrete audit response type
     * @param sessionId the session identifier
     * @param eventType optional event type filter (e.g., "TOOL_CALL", "LLM_CALL")
     * @return audit trail response
     */
    @GetMapping("/sessions/{sessionId}/audit")
    public <AUDIT extends BaseAuditTrailResponse> AUDIT getAuditTrail(
            @PathVariable final String sessionId,
            @RequestParam(required = false) final String eventType) {
        return runtimeService.getAuditTrail(sessionId, eventType);
    }

    // ─── Extension points ─────────────────────────────────────────────────────

    /**
     * Returns the SSE emitter timeout in milliseconds.
     *
     * <p>Override to set a longer or shorter timeout for your agent's expected response
     * times. Defaults to 10 minutes.
     *
     * @return the emitter timeout in milliseconds
     */
    protected long sseTimeoutMillis() {
        return DEFAULT_SSE_TIMEOUT_MILLIS;
    }

    /**
     * Called before session creation to perform request validation or enrichment.
     * Override to add domain-specific validation.
     *
     * @param request  the create-session request
     * @param httpReq  the HTTP servlet request
     */
    protected void onBeforeCreateSession(final REQ request, final HttpServletRequest httpReq) {
        // Default: no-op
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void enrichWithTenant(final REQ request, final HttpServletRequest httpReq) {
        if (request.getTenantId() != null && !request.getTenantId().isBlank()) {
            return;
        }
        if (tenantResolver != null) {
            String resolved = tenantResolver.resolve(httpReq);
            if (resolved != null && !resolved.isBlank()) {
                request.setTenantId(resolved);
            }
        }
    }
}
