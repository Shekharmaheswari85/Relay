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
package io.agentcore.pipeline;

import java.util.List;
import java.util.Map;

import io.agentcore.checkpoint.BaseChatHistoryManager;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.repository.BaseAgentSessionRepository;
import io.agentcore.session.SessionContextManager;
import io.agentcore.session.SessionStatus;
import io.agentcore.stream.PipelineEmitter;
import io.agentcore.stream.ToolProgressPublisher;

import lombok.extern.slf4j.Slf4j;

/**
 * Executes the ordered sequence of pre-LLM pipeline steps for a single agent turn and hands
 * off to the domain orchestrator for LLM streaming.
 *
 * <p>{@code BaseAgentPipelineExecutor} sits between the HTTP layer
 * ({@link io.agentcore.web.BaseAgentController}) and the orchestration layer. It implements a
 * fixed template-method pipeline that every agent turn passes through before reaching the LLM:
 *
 * <ol>
 *   <li><b>Session validation</b> — asserts the session is
 *       {@link io.agentcore.session.SessionStatus#ACTIVE} and has not exceeded the
 *       {@link #maxToolCalls} limit. Throws {@link IllegalStateException} on violation.</li>
 *   <li><b>Chat history append</b> — appends the incoming user message to the session's
 *       chat history store via {@link BaseChatHistoryManager}.</li>
 *   <li><b>Session refresh</b> — reloads the session's context JSON and domain-specific fields
 *       from the database. Override {@link #onSessionRefreshed} to copy domain-specific fields.</li>
 *   <li><b>Missing-context gate</b> — calls {@link #resolveMissingContextFields} to detect
 *       required context that has not yet been collected. When the list is non-empty,
 *       {@link #handleMissingContext} produces the SSE response and the pipeline short-circuits.</li>
 *   <li><b>Domain-specific gates</b> — calls {@link #applyDomainSpecificGates}, which returns
 *       {@code true} if it handled the response (short-circuit) or {@code false} to continue.</li>
 *   <li><b>Orchestrator routing</b> — delegates to {@link #routeToOrchestrator}.</li>
 * </ol>
 *
 * <h3>How to extend</h3>
 * <p>Declare a concrete {@code @Component} subclass in your agent module:
 *
 * <pre>{@code
 * @Component
 * public class OrderPipelineExecutor
 *         extends BaseAgentPipelineExecutor<OrderSessionDO> {
 *
 *     private final OrderAgentOrchestrator orchestrator;
 *
 *     public OrderPipelineExecutor(
 *             OrderSessionRepository repository,
 *             SessionContextManager contextManager,
 *             OrderChatHistoryManager historyManager,
 *             ToolProgressPublisher progressPublisher,
 *             OrderAgentOrchestrator orchestrator,
 *             @Value("${agent.order.max-tool-calls:200}") int maxToolCalls) {
 *         super(repository, contextManager, historyManager, progressPublisher, maxToolCalls);
 *         this.orchestrator = orchestrator;
 *     }
 *
 *     @Override
 *     protected void routeToOrchestrator(
 *             OrderSessionDO session, String sessionId, String messageContent,
 *             PipelineEmitter emitter) {
 *         Map<String, Object> context =
 *                 sessionContextManager.parseContextJson(session.getContextJson());
 *         orchestrator.route(session, sessionId, messageContent, emitter, context);
 *     }
 *
 *     @Override
 *     protected boolean applyDomainSpecificGates(
 *             OrderSessionDO session, String sessionId,
 *             Map<String, Object> context, PipelineEmitter emitter) {
 *         if (Boolean.TRUE.equals(context.get("awaitingConfirmation"))) {
 *             emitter.sendConfirmationRequired("Please confirm the pending action.");
 *             emitter.sendDone();
 *             return true;
 *         }
 *         return false;
 *     }
 *
 *     @Override
 *     protected void handleMissingContext(
 *             OrderSessionDO session, String sessionId,
 *             List<String> missingFields, PipelineEmitter emitter) {
 *         emitter.sendMessage("Please provide your order number to continue.");
 *         emitter.sendDone();
 *     }
 * }
 * }</pre>
 *
 * @param <S> the session entity type; must extend {@link BaseAgentSession}
 */
@Slf4j
public abstract class BaseAgentPipelineExecutor<S extends BaseAgentSession> {

    protected static final int DEFAULT_MAX_TOOL_CALLS = 200;
    protected static final String CONTEXT_KEY_TOTAL_TOOL_CALLS = "totalToolCalls";

    protected final BaseAgentSessionRepository<S> sessionRepository;
    protected final SessionContextManager sessionContextManager;
    protected final BaseChatHistoryManager<S> chatHistoryManager;
    protected final ToolProgressPublisher toolProgressPublisher;
    protected final int maxToolCalls;

    protected BaseAgentPipelineExecutor(
            final BaseAgentSessionRepository<S> sessionRepository,
            final SessionContextManager sessionContextManager,
            final BaseChatHistoryManager<S> chatHistoryManager,
            final ToolProgressPublisher toolProgressPublisher,
            final int maxToolCalls) {
        this.sessionRepository = sessionRepository;
        this.sessionContextManager = sessionContextManager;
        this.chatHistoryManager = chatHistoryManager;
        this.toolProgressPublisher = toolProgressPublisher;
        this.maxToolCalls = maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;
    }

    /**
     * Executes the full pre-LLM pipeline for a single user message, writing all SSE events
     * to {@code emitter}.
     *
     * <p>This is the single entry point called by {@link io.agentcore.executor.AgentExecutor}
     * for the {@code sendMessage} API. The method runs synchronously on the calling virtual
     * thread. Any {@link IllegalStateException} thrown during validation (step 1) propagates
     * to the caller; all other failures are emitted as {@code error} SSE events.
     *
     * @param session        the current session entity, already loaded by the executor
     * @param sessionId      the session identifier used for logging and SSE routing
     * @param messageContent the raw user message for this turn; must not be {@code null} or blank
     * @param emitter        the {@link PipelineEmitter} to which all SSE events are written
     * @throws IllegalStateException if the session is not {@link io.agentcore.session.SessionStatus#ACTIVE}
     *                               or has exceeded {@link #maxToolCalls}
     */
    public void execute(
            final S session,
            final String sessionId,
            final String messageContent,
            final PipelineEmitter emitter) {
        log.info("Processing message for session {}", sessionId);

        validateSessionActive(session);

        chatHistoryManager.appendChatTurn(session, "user", messageContent);

        refreshSessionFromDb(session, sessionId);

        List<String> missingFields = resolveMissingContextFields(session);
        if (!missingFields.isEmpty()) {
            handleMissingContext(session, sessionId, missingFields, emitter);
            return;
        }

        Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
        boolean handled = applyDomainSpecificGates(session, sessionId, context, emitter);
        if (handled) {
            return;
        }

        toolProgressPublisher.emitThinking(sessionId, "Analyzing request intent");

        routeToOrchestrator(session, sessionId, messageContent, emitter);
    }

    // ─── Abstract methods ─────────────────────────────────────────────────────

    /**
     * Routes the prepared turn to the domain orchestrator for LLM execution.
     *
     * <p>Implementations call the orchestrator's {@code route} method with the session,
     * session ID, user message, emitter, and parsed context. The method runs synchronously;
     * the orchestrator writes all SSE events to {@code emitter} before this method returns.
     *
     * @param session        the current session entity with refreshed state
     * @param sessionId      the session identifier
     * @param messageContent the raw user message for this turn
     * @param emitter        the pipeline emitter to write SSE events to
     */
    protected abstract void routeToOrchestrator(
            S session,
            String sessionId,
            String messageContent,
            PipelineEmitter emitter);

    /**
     * Applies domain-specific business gates before the turn reaches the LLM.
     *
     * <p>Return {@code true} to short-circuit the pipeline — for example to prompt the user
     * for confirmation, redirect to a different workflow, or emit a structured decision
     * response. When returning {@code true}, this method is responsible for calling
     * {@link PipelineEmitter#sendDone()} so the SSE stream terminates cleanly.
     * Return {@code false} to allow the pipeline to continue to the orchestrator.
     *
     * @param session   the current session entity with refreshed state
     * @param sessionId the session identifier
     * @param context   the parsed session context map (never {@code null}; may be empty)
     * @param emitter   the pipeline emitter available for writing gate-response events
     * @return {@code true} if this method handled the response and the pipeline should
     *         stop; {@code false} to proceed to {@link #routeToOrchestrator}
     */
    protected abstract boolean applyDomainSpecificGates(
            S session, String sessionId, Map<String, Object> context, PipelineEmitter emitter);

    /**
     * Produces the SSE response when required context fields are missing.
     *
     * <p>Called when {@link #resolveMissingContextFields} returns a non-empty list.
     * Implementations must write at minimum a {@code message} event and call
     * {@link PipelineEmitter#sendDone()} to terminate the stream.
     *
     * @param session       the current session entity
     * @param sessionId     the session identifier
     * @param missingFields the list of missing context field names; never {@code null} or empty
     * @param emitter       the pipeline emitter to write events to
     */
    protected abstract void handleMissingContext(
            S session, String sessionId, List<String> missingFields, PipelineEmitter emitter);

    // ─── Overridable hooks ────────────────────────────────────────────────────

    /**
     * Returns the list of context field names that are required but not yet present in the session.
     *
     * <p>When the returned list is non-empty, the pipeline short-circuits and calls
     * {@link #handleMissingContext}. Override to check for agent-specific required fields such
     * as an order number, customer ID, or product selection. The default implementation always
     * returns an empty list (no required fields).
     *
     * @param session the current session entity with refreshed context JSON
     * @return a list of missing field names; an empty list allows the pipeline to continue
     */
    protected List<String> resolveMissingContextFields(final S session) {
        return List.of();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Validates that the session is active and has not exceeded the maximum tool-call budget.
     *
     * @param session the session entity to validate
     * @throws IllegalStateException if the session status is not
     *                               {@link io.agentcore.session.SessionStatus#ACTIVE}, or if the
     *                               {@code totalToolCalls} context key is present and its value
     *                               meets or exceeds {@link #maxToolCalls}
     */
    protected void validateSessionActive(final S session) {
        if (!SessionStatus.ACTIVE.name().equals(session.getStatus())) {
            throw new IllegalStateException(
                    "Session " + session.getSessionId() + " is not active (status: " + session.getStatus() + ")");
        }
        Map<String, Object> context = sessionContextManager.parseContextJson(session.getContextJson());
        Object toolCalls = context.get(CONTEXT_KEY_TOTAL_TOOL_CALLS);
        if (toolCalls instanceof Number number && number.intValue() >= maxToolCalls) {
            throw new IllegalStateException(
                    "Session " + session.getSessionId() + " exceeded max tool calls (" + maxToolCalls + ")");
        }
    }

    /**
     * Reloads the session's {@code contextJson} and domain-specific fields from the database.
     *
     * <p>After this method returns, the in-memory {@code session} object reflects the latest
     * persisted state. Override {@link #onSessionRefreshed} to copy additional domain-specific
     * fields beyond {@code contextJson}.
     *
     * @param session   the in-memory session object to update in place
     * @param sessionId the session identifier used to locate the record in the repository
     */
    protected void refreshSessionFromDb(final S session, final String sessionId) {
        sessionRepository.findBySessionId(sessionId).ifPresent(refreshed -> {
            session.setContextJson(refreshed.getContextJson());
            onSessionRefreshed(session, refreshed);
        });
    }

    /**
     * Hook invoked after the session has been refreshed from the database.
     *
     * <p>Override to copy domain-specific fields from the freshly loaded {@code refreshed}
     * instance into the in-memory {@code session} object. The default implementation is a no-op.
     *
     * @param session   the in-memory session object that is being updated
     * @param refreshed the freshly loaded session entity from the database; do not mutate
     */
    protected void onSessionRefreshed(final S session, final S refreshed) {
    }
}
