/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

import java.util.Objects;

import io.agentcore.agent.AgentExecutionContext;
import io.agentcore.agent.BaseSubAgent;
import io.agentcore.model.BaseAgentSession;
import io.agentcore.stream.PipelineEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Base class for sub-agents that delegate execution to a remote agentcore service over HTTP.
 *
 * <p>Extend this class when an orchestration step should be handed off to a separate
 * microservice rather than handled locally. The remote service must expose the standard
 * agentcore REST API (session creation + SSE message endpoint).
 *
 * <p>The delegation flow per turn:
 * <ol>
 *   <li>Create a session on the remote agent ({@code POST {basePath}/sessions})</li>
 *   <li>Forward the current message and proxy the {@link SseEvent}s back via the local
 *       {@link PipelineEmitter}</li>
 *   <li>Delete the remote session after streaming completes or errors</li>
 * </ol>
 *
 * <h3>Minimal implementation</h3>
 * <pre>{@code
 * @Component
 * public class InventorySubAgent extends RemoteAgentSubAgent<MySession, MyStep> {
 *
 *     public InventorySubAgent(AgentClientRegistry registry) {
 *         super(registry.get("inventory-agent"));
 *     }
 *
 *     @Override
 *     public String name() { return "InventorySubAgent"; }
 *
 *     @Override
 *     public boolean canHandle(MySession session, MyStep step) {
 *         return step == MyStep.INVENTORY_CHECK;
 *     }
 *
 *     // Optional: customise what is sent to the remote agent
 *     @Override
 *     protected String resolveAgentId(AgentExecutionContext<MySession> ctx) {
 *         return "inventory-check-agent";
 *     }
 * }
 * }</pre>
 *
 * @param <S>    the local session entity type
 * @param <STEP> the local workflow step type
 * @see AgentClient
 * @see AgentClientRegistry
 */
@Slf4j
public abstract class RemoteAgentSubAgent<S extends BaseAgentSession, STEP>
        implements BaseSubAgent<S, STEP> {

    private final AgentClient agentClient;

    /**
     * Creates a remote sub-agent that delegates to the given {@link AgentClient}.
     *
     * @param agentClient the pre-configured client for the remote agent; never null
     */
    protected RemoteAgentSubAgent(final AgentClient agentClient) {
        this.agentClient = Objects.requireNonNull(agentClient, "AgentClient must not be null");
    }

    /**
     * Always returns {@code true}: this sub-agent bypasses the local LLM call and
     * delegates the full SSE stream to the remote agent.
     */
    @Override
    public final boolean handlesExecution() {
        return true;
    }

    /**
     * Creates a remote session, forwards the message, and proxies all SSE events from the
     * remote agent to the local {@link PipelineEmitter}.
     *
     * <p>All HTTP calls block the calling virtual thread — no platform threads are consumed
     * during I/O waits. The remote session is automatically deleted after streaming completes
     * or errors. If session deletion fails the error is logged and swallowed.
     *
     * @param ctx the local agent execution context; the {@link PipelineEmitter} in the context
     *            receives all proxied SSE events
     */
    @Override
    public void execute(final AgentExecutionContext<S> ctx) {
        String agentId = resolveAgentId(ctx);
        String createdBy = resolveCreatedBy(ctx);
        String message = ctx.message();
        PipelineEmitter emitter = ctx.emitter();

        log.info("A2A delegation to '{}': agentId={} sessionId={}",
                agentClient.getName(), agentId, ctx.sessionId());

        String remoteSessionId = null;
        try {
            remoteSessionId = agentClient.createSession(agentId, createdBy);
            if (remoteSessionId == null) {
                throw new IllegalStateException(
                        "Remote agent '" + agentClient.getName() + "' did not return a sessionId");
            }
            log.debug("A2A remote session created: remoteSessionId={}", remoteSessionId);

            agentClient.sendMessage(remoteSessionId, message, event -> {
                String eventName = event.event();
                String data = event.data();
                switch (eventName) {
                    case "done"                -> { /* terminal — orchestrator sends done */ }
                    case "message"             -> emitter.sendMessage(data);
                    case "thinking", "follow_up_questions" -> emitter.sendThinking(data);
                    case "tool_progress"       -> emitter.sendToolProgress(data);
                    case "stage"               -> emitter.sendStage("remote_agent", data, 50);
                    case "error"               -> emitter.sendError(data);
                    default                    -> log.debug("Unrecognised SSE event '{}' from remote agent", eventName);
                }
            });

            agentClient.deleteSession(remoteSessionId);

        } catch (Exception ex) {
            log.error("A2A execution failed for '{}': {}", agentClient.getName(), ex.getMessage(), ex);
            emitter.sendError("Remote agent delegation failed: " + ex.getMessage());
            if (remoteSessionId != null) {
                agentClient.deleteSession(remoteSessionId);
            }
        }
    }

    // ─── Extension points ─────────────────────────────────────────────────────

    /**
     * Returns the {@code agentId} sent to the remote agent's session creation endpoint.
     *
     * <p>Default: the local session's {@code agentId}, or the remote client's logical name
     * if the session has none. Override to supply a different value.
     *
     * @param ctx the local agent execution context
     * @return the agent ID for the remote session
     */
    protected String resolveAgentId(final AgentExecutionContext<S> ctx) {
        String localAgentId = ctx.session() != null ? ctx.session().getAgentId() : null;
        return localAgentId != null ? localAgentId : agentClient.getName();
    }

    /**
     * Returns the {@code createdBy} value sent to the remote agent's session creation endpoint.
     *
     * <p>Default: {@code "a2a:" + localSessionId} so the remote agent can trace the call back
     * to its origin. Override to supply a different value.
     *
     * @param ctx the local agent execution context
     * @return the creator identifier for the remote session
     */
    protected String resolveCreatedBy(final AgentExecutionContext<S> ctx) {
        return "a2a:" + ctx.sessionId();
    }
}
