/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.executor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.relay.dto.AgentMetadataDTO;
import io.relay.dto.BaseAuditTrailResponse;
import io.relay.dto.BaseBulkDeleteResponse;
import io.relay.dto.BaseCreateSessionRequest;
import io.relay.dto.BaseCreateSessionResponse;
import io.relay.dto.BaseDeleteSessionResponse;
import io.relay.dto.BaseResumeSessionResponse;
import io.relay.dto.BaseSessionStatusResponse;
import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;
import io.relay.stream.PipelineEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Base service that wires the {@link AgentRegistry} and the session store together, providing
 * a unified API surface for all session lifecycle operations across every registered agent.
 *
 * <p>{@code BaseAgentRuntimeService} is the layer that {@link io.relay.web.BaseAgentController}
 * delegates to. It owns two core responsibilities:
 * <ol>
 *   <li><b>Routing by agent ID</b> — for operations that carry an explicit agent ID (e.g., session
 *       creation), the service resolves the matching {@link AgentExecutor} from the
 *       {@link AgentRegistry} and delegates.</li>
 *   <li><b>Session-based resolution</b> — for operations that operate on an existing session (e.g.,
 *       {@link #sendMessage}, {@link #deleteSession}), the service loads the session from the
 *       repository, reads the persisted {@code agentId} field (or falls back to parsing it from the
 *       session's context JSON), and resolves the correct executor. This means callers never need to
 *       know which agent owns a session.</li>
 * </ol>
 *
 * <h3>How to extend</h3>
 * <p>Declare a concrete {@code @Service} subclass in your application module. The only mandatory
 * override is {@link #getDefaultAgentId()}, which identifies the agent used for operations that do
 * not carry a session or explicit agent ID (e.g., {@link #listSessions} and
 * {@link #bulkDeleteSessions}):
 *
 * <pre>{@code
 * @Service
 * public class OrderAgentRuntimeService
 *         extends BaseAgentRuntimeService<OrderSessionDO> {
 *
 *     public OrderAgentRuntimeService(
 *             AgentRegistry registry,
 *             OrderSessionRepository repository,
 *             ObjectMapper objectMapper) {
 *         super(registry, repository, objectMapper);
 *     }
 *
 *     @Override
 *     protected String getDefaultAgentId() {
 *         return "order-agent";
 *     }
 * }
 * }</pre>
 *
 * @param <S> the session entity type; must extend {@link BaseAgentSession}
 * @see AgentRegistry
 * @see AgentExecutor
 */
@Slf4j
public abstract class BaseAgentRuntimeService<S extends BaseAgentSession> {

    private static final String CONTEXT_KEY_AGENT_ID = "agentId";

    protected final AgentRegistry agentRegistry;
    protected final BaseAgentSessionRepository<S> sessionRepository;
    protected final ObjectMapper objectMapper;

    protected BaseAgentRuntimeService(
            final AgentRegistry agentRegistry,
            final BaseAgentSessionRepository<S> sessionRepository,
            final ObjectMapper objectMapper) {
        this.agentRegistry = agentRegistry;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the agent ID used when no explicit agent ID is available.
     *
     * <p>This value is used as the routing target for operations that are not scoped to a
     * specific session — namely {@link #listSessions(String)} and
     * {@link #bulkDeleteSessions(List)} — and as the fallback when a session's persisted
     * {@code agentId} field is absent and no {@code agentId} key is found in its context JSON.
     *
     * @return the default agent identifier; must match a registered {@link AgentExecutor#agentId()};
     *         must not be {@code null} or blank
     */
    protected abstract String getDefaultAgentId();

    /**
     * Returns metadata for all agents currently registered in the {@link AgentRegistry}.
     *
     * <p>The returned list is assembled by collecting {@link AgentExecutor#metadata()} from
     * every registered executor. Use this to power agent discovery APIs or capability panels.
     *
     * @return the list of {@link AgentMetadataDTO} objects; never empty unless no agents are
     *         registered
     */
    public List<AgentMetadataDTO> getAgentMetadata() {
        return agentRegistry.listExecutors().stream()
                .map(AgentExecutor::metadata)
                .toList();
    }

    /**
     * Creates a new session, routing to the agent identified by
     * {@link BaseCreateSessionRequest#agentId}.
     *
     * <p>If the request does not specify an agent ID (null or blank), the service falls back to
     * {@link #getDefaultAgentId()}. The resolved executor's
     * {@link AgentExecutor#createSession(BaseCreateSessionRequest)} is then called with the
     * original request object.
     *
     * @param <REQ>   the concrete request type
     * @param <RES>   the concrete response type; must extend {@link BaseCreateSessionResponse}
     * @param request the session creation request; may specify an agent ID to override the default
     * @return the creation response with the new session ID
     */
    public <REQ extends BaseCreateSessionRequest, RES extends BaseCreateSessionResponse> RES createSession(
            final REQ request) {
        String requestedAgentId = request != null && request.getAgentId() != null
                ? request.getAgentId().trim()
                : "";
        String resolvedAgentId = requestedAgentId.isBlank() ? getDefaultAgentId() : requestedAgentId;
        AgentExecutor<REQ, RES, ?, ?, ?, ?, ?> executor = agentRegistry.resolve(resolvedAgentId);
        return executor.createSession(request);
    }

    /**
     * Sends a user message to an existing session, routing to the session's owning agent.
     *
     * <p>The owning agent is determined by loading the session from the repository and reading
     * its persisted {@code agentId} field. The resolved executor's
     * {@link AgentExecutor#sendMessage(String, String, PipelineEmitter)} writes SSE events
     * directly to the provided {@link PipelineEmitter}.
     *
     * @param sessionId the identifier of the target session
     * @param content   the raw user message text
     * @param emitter   the {@link PipelineEmitter} to which SSE events are written
     */
    public void sendMessage(final String sessionId, final String content, final PipelineEmitter emitter) {
        resolveBySessionId(sessionId).sendMessage(sessionId, content, emitter);
    }

    /**
     * Lists sessions managed by the default agent, optionally filtered by status.
     *
     * @param <STATUS> the concrete status response type
     * @param status   an optional {@link io.relay.session.SessionStatus} name; pass
     *                 {@code null} or blank to return all sessions
     * @return the list of matching session status summaries
     */
    public <STATUS extends BaseSessionStatusResponse> List<STATUS> listSessions(final String status) {
        AgentExecutor<?, ?, STATUS, ?, ?, ?, ?> executor = agentRegistry.resolve(getDefaultAgentId());
        return executor.listSessions(status);
    }

    /**
     * Permanently deletes a session, routing to the session's owning agent.
     *
     * @param <DEL>     the concrete deletion response type
     * @param sessionId the identifier of the session to delete
     * @return the deletion result
     * @throws IllegalArgumentException if the session does not exist
     */
    public <DEL extends BaseDeleteSessionResponse> DEL deleteSession(final String sessionId) {
        AgentExecutor<?, ?, ?, DEL, ?, ?, ?> executor = resolveBySessionId(sessionId);
        return executor.deleteSession(sessionId);
    }

    /**
     * Permanently deletes multiple sessions using the default agent's bulk-delete implementation.
     *
     * @param <BULK>      the concrete bulk-delete response type
     * @param sessionIds  the list of session identifiers to delete; must not be {@code null}
     * @return the bulk deletion result with a count of deleted sessions
     */
    public <BULK extends BaseBulkDeleteResponse> BULK bulkDeleteSessions(final List<String> sessionIds) {
        AgentExecutor<?, ?, ?, ?, BULK, ?, ?> executor = agentRegistry.resolve(getDefaultAgentId());
        return executor.bulkDeleteSessions(sessionIds);
    }

    /**
     * Retrieves the current status and metadata for a session, routing to the session's owning
     * agent.
     *
     * @param <STATUS>  the concrete status response type
     * @param sessionId the identifier of the session to inspect
     * @return the session status response
     * @throws IllegalArgumentException if the session does not exist
     */
    public <STATUS extends BaseSessionStatusResponse> STATUS getSession(final String sessionId) {
        AgentExecutor<?, ?, STATUS, ?, ?, ?, ?> executor = resolveBySessionId(sessionId);
        return executor.getSession(sessionId);
    }

    /**
     * Resumes a paused session, routing to the session's owning agent.
     *
     * @param <RESUME>  the concrete resume response type
     * @param sessionId the identifier of the paused session to resume
     * @return the resume result
     * @throws IllegalArgumentException if the session does not exist or is not resumable
     */
    public <RESUME extends BaseResumeSessionResponse> RESUME resumeSession(final String sessionId) {
        AgentExecutor<?, ?, ?, ?, ?, RESUME, ?> executor = resolveBySessionId(sessionId);
        return executor.resumeSession(sessionId);
    }

    /**
     * Retrieves the audit trail for a session, routing to the session's owning agent.
     *
     * @param <AUDIT>   the concrete audit trail response type
     * @param sessionId the identifier of the session whose audit trail to fetch
     * @param eventType an optional event-type name to restrict the results; pass {@code null}
     *                  to return all event types
     * @return the audit trail response
     * @throws IllegalArgumentException if the session does not exist
     */
    public <AUDIT extends BaseAuditTrailResponse> AUDIT getAuditTrail(
            final String sessionId, final String eventType) {
        AgentExecutor<?, ?, ?, ?, ?, ?, AUDIT> executor = resolveBySessionId(sessionId);
        return executor.getAuditTrail(sessionId, eventType);
    }

    /**
     * Resolves the {@link AgentExecutor} that owns the given session.
     *
     * <p>Loads the session from the repository, reads its {@code agentId} field, and calls
     * {@link AgentRegistry#resolve(String)}. If the persisted {@code agentId} is absent, the
     * method falls back to {@link #resolveAgentIdFromContext(String)} before resolving.
     *
     * @param <E>       the target executor type
     * @param sessionId the session identifier to look up
     * @return the executor that owns the session; never {@code null}
     * @throws IllegalArgumentException if the session does not exist or the resolved agent ID
     *                                  is not registered
     */
    protected <E extends AgentExecutor<?, ?, ?, ?, ?, ?, ?>> E resolveBySessionId(final String sessionId) {
        S session = sessionRepository
                .findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        String persistedAgentId = session.getAgentId();
        String agentId = (persistedAgentId != null && !persistedAgentId.isBlank())
                ? persistedAgentId
                : resolveAgentIdFromContext(session.getContextJson());
        return agentRegistry.resolve(agentId);
    }

    /**
     * Extracts the agent ID from the session's context JSON, falling back to
     * {@link #getDefaultAgentId()} when the key is absent or blank.
     *
     * <p>The context JSON is expected to contain a top-level {@code "agentId"} string key.
     * Malformed or empty JSON silently returns the default agent ID.
     *
     * @param contextJson the raw JSON string from the session entity; may be {@code null}
     * @return the agent ID extracted from context, or the default agent ID; never {@code null}
     */
    protected String resolveAgentIdFromContext(final String contextJson) {
        Map<String, Object> context = parseContextJson(contextJson);
        Object value = context.get(CONTEXT_KEY_AGENT_ID);
        if (value instanceof String agentId && !agentId.isBlank()) {
            return agentId;
        }
        return getDefaultAgentId();
    }

    /**
     * Parses a JSON string into a {@link Map}{@code <String, Object>}.
     *
     * <p>Returns an empty map for {@code null}, blank input, or malformed JSON. Parse
     * failures are logged at {@code WARN} level and do not throw.
     *
     * @param contextJson the raw JSON string to parse; may be {@code null}
     * @return a mutable map of the parsed key-value pairs; never {@code null}
     */
    protected Map<String, Object> parseContextJson(final String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(contextJson, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse context JSON: {}", ex.getMessage());
            return new HashMap<>();
        }
    }
}
