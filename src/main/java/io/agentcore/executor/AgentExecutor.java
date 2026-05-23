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
package io.agentcore.executor;

import java.util.List;

import io.agentcore.dto.AgentMetadataDTO;
import io.agentcore.dto.BaseAuditTrailResponse;
import io.agentcore.dto.BaseBulkDeleteResponse;
import io.agentcore.dto.BaseCreateSessionRequest;
import io.agentcore.dto.BaseCreateSessionResponse;
import io.agentcore.dto.BaseDeleteSessionResponse;
import io.agentcore.dto.BaseResumeSessionResponse;
import io.agentcore.dto.BaseSessionStatusResponse;
import io.agentcore.stream.PipelineEmitter;

/**
 * Defines the full session lifecycle contract for a single agent in the multi-agent runtime.
 *
 * <p>Every named agent in the system implements this interface and registers itself as a Spring
 * bean. {@link AgentRegistry} collects all beans of this type at startup and indexes them by
 * their {@link #agentId()}. {@link BaseAgentRuntimeService} then routes each API request to the
 * correct executor by resolving the agent ID from the request or from the persisted session.
 *
 * <p>The interface is deliberately typed over seven generic parameters so that each agent can
 * expose its own request/response DTOs without a common base requiring shared fields. Callers
 * that only need to invoke the contract without knowing the concrete types use the raw wildcard
 * form {@code AgentExecutor<?, ?, ?, ?, ?, ?, ?>}.
 *
 * <h3>How to implement</h3>
 * <p>Annotate the implementation as a Spring component so that {@link AgentRegistry} discovers
 * it automatically. Provide a stable, lowercase kebab-case {@link #agentId()} — this value is
 * stored in the session and used for all subsequent routing:
 *
 * <pre>{@code
 * @Component
 * public class OrderAgentExecutor implements AgentExecutor<
 *         CreateOrderSessionRequest,
 *         CreateOrderSessionResponse,
 *         OrderSessionStatusResponse,
 *         DeleteOrderSessionResponse,
 *         BulkDeleteOrderSessionResponse,
 *         ResumeOrderSessionResponse,
 *         OrderAuditTrailResponse> {
 *
 *     @Override
 *     public String agentId() {
 *         return "order-agent";
 *     }
 *
 *     @Override
 *     public AgentMetadataDTO metadata() {
 *         return AgentMetadataDTO.builder()
 *                 .agentId("order-agent")
 *                 .name("Order Agent")
 *                 .description("Manages order enquiries and modifications")
 *                 .build();
 *     }
 *
 *     @Override
 *     public CreateOrderSessionResponse createSession(CreateOrderSessionRequest request) {
 *         return sessionLifecycleService.create(request);
 *     }
 *
 *     @Override
 *     public void sendMessage(String sessionId, String content, PipelineEmitter emitter) {
 *         pipelineExecutor.execute(sessionId, content, emitter);
 *     }
 *
 *     // ... remaining lifecycle methods
 * }
 * }</pre>
 *
 * @param <REQ>    type of the session creation request; must extend {@link BaseCreateSessionRequest}
 * @param <RES>    type of the session creation response; must extend {@link BaseCreateSessionResponse}
 * @param <STATUS> type of the session status response; must extend {@link BaseSessionStatusResponse}
 * @param <DEL>    type of the single-session deletion response; must extend {@link BaseDeleteSessionResponse}
 * @param <BULK>   type of the bulk deletion response; must extend {@link BaseBulkDeleteResponse}
 * @param <RESUME> type of the session resume response; must extend {@link BaseResumeSessionResponse}
 * @param <AUDIT>  type of the audit trail response; must extend {@link BaseAuditTrailResponse}
 * @see AgentRegistry
 * @see BaseAgentRuntimeService
 */
public interface AgentExecutor<
        REQ extends BaseCreateSessionRequest,
        RES extends BaseCreateSessionResponse,
        STATUS extends BaseSessionStatusResponse,
        DEL extends BaseDeleteSessionResponse,
        BULK extends BaseBulkDeleteResponse,
        RESUME extends BaseResumeSessionResponse,
        AUDIT extends BaseAuditTrailResponse> {

    /**
     * Returns the stable, unique identifier for this agent.
     *
     * <p>The value is stored in every session created by this executor, persisted across
     * restarts, and used as the routing key in {@link AgentRegistry}. Use a lowercase
     * kebab-case string (e.g., {@code "order-agent"}) and never change it once sessions
     * have been persisted.
     *
     * @return the agent's unique identifier; must not be {@code null} or blank
     */
    String agentId();

    /**
     * Returns descriptive metadata about this agent's capabilities for discovery APIs.
     *
     * <p>The returned {@link AgentMetadataDTO} is surfaced by
     * {@link BaseAgentRuntimeService#getAgentMetadata()} and can be consumed by UI capability
     * panels. At minimum, populate {@code agentId}, {@code name}, and {@code description}.
     *
     * @return an {@link AgentMetadataDTO} describing this agent; must not be {@code null}
     */
    AgentMetadataDTO metadata();

    /**
     * Creates a new agent session from the supplied request.
     *
     * <p>Implementations persist a new {@link io.agentcore.model.BaseAgentSession} row with
     * {@link io.agentcore.session.SessionStatus#ACTIVE} status and return a response that
     * includes the generated session ID. The session ID is the caller's handle for all
     * subsequent operations.
     *
     * @param request the session creation request carrying initial context, user identity,
     *                and any agent-specific configuration
     * @return the creation response with the new session ID
     * @throws IllegalArgumentException on validation failure
     */
    RES createSession(REQ request);

    /**
     * Sends a user message to an existing session and writes the agent response as SSE events
     * to the supplied {@link PipelineEmitter}.
     *
     * <p>Implementations drive the full agent pipeline synchronously on the calling virtual
     * thread. All {@code stage}, {@code message}, {@code follow_up_questions},
     * {@code confirmation_required}, and {@code done} events are emitted directly to
     * {@code emitter}. Failures should be written as {@code error} events and must not throw.
     *
     * @param sessionId the identifier of the target session
     * @param content   the raw user message text
     * @param emitter   the {@link PipelineEmitter} to which SSE events are written
     */
    void sendMessage(String sessionId, String content, PipelineEmitter emitter);

    /**
     * Lists sessions managed by this agent, optionally filtered by status.
     *
     * @param status an optional {@link io.agentcore.session.SessionStatus} name to filter by;
     *               pass {@code null} or blank to return all sessions
     * @return the list of matching session status summaries
     */
    List<STATUS> listSessions(String status);

    /**
     * Permanently deletes a session and its associated data.
     *
     * @param sessionId the identifier of the session to delete
     * @return the deletion result
     * @throws IllegalArgumentException if the session does not exist
     */
    DEL deleteSession(String sessionId);

    /**
     * Permanently deletes multiple sessions in a single operation.
     *
     * @param sessionIds the list of session identifiers to delete; must not be {@code null}
     * @return the bulk deletion result with a count of deleted sessions
     */
    BULK bulkDeleteSessions(List<String> sessionIds);

    /**
     * Retrieves the current status and metadata for a single session.
     *
     * @param sessionId the identifier of the session to inspect
     * @return the session status response
     * @throws IllegalArgumentException if the session does not exist
     */
    STATUS getSession(String sessionId);

    /**
     * Resumes a {@link io.agentcore.session.SessionStatus#PAUSED} session, returning it to
     * {@link io.agentcore.session.SessionStatus#ACTIVE} status.
     *
     * @param sessionId the identifier of the paused session to resume
     * @return the resume result
     * @throws IllegalArgumentException if the session is not in a resumable state
     */
    RESUME resumeSession(String sessionId);

    /**
     * Retrieves the audit event trail for a session, optionally filtered to a specific event type.
     *
     * @param sessionId the identifier of the session whose audit trail to fetch
     * @param eventType an optional event-type name to restrict the results (e.g., {@code "tool_call"});
     *                  pass {@code null} to return all event types
     * @return the audit trail response
     * @throws IllegalArgumentException if the session does not exist
     */
    AUDIT getAuditTrail(String sessionId, String eventType);
}
