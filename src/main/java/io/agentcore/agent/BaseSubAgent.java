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
package io.agentcore.agent;

import java.util.List;
import java.util.Map;

import io.agentcore.model.BaseAgentSession;

/**
 * Generic contract for sub-agents in multi-agent orchestration pipelines.
 * <p>
 * Each sub-agent owns a slice of the workflow. The orchestrator selects the
 * appropriate agent per turn and injects its system prompt into the LLM system
 * message before execution.
 * <p>
 * Sub-agents are intentionally lightweight: they carry no LLM execution logic
 * by default. All streaming machinery lives in the orchestrator.
 *
 * <h3>Implementation example</h3>
 * <pre>{@code
 * @Component
 * @Order(10)
 * public class ConfigSubAgent implements BaseSubAgent<MySessionDO, MyStepEnum> {
 *
 *     @Override
 *     public String name() {
 *         return "config-agent";
 *     }
 *
 *     @Override
 *     public boolean canHandle(MySessionDO session, MyStepEnum currentStep) {
 *         return currentStep == MyStepEnum.CONFIG_STEP;
 *     }
 *
 *     @Override
 *     public String systemPrompt(MySessionDO session, Map<String, Object> context) {
 *         return "You are the configuration sub-agent. Help users configure their settings.";
 *     }
 * }
 * }</pre>
 *
 * @param <S>    the session entity type
 * @param <STEP> the workflow step type (typically an enum)
 */
public interface BaseSubAgent<S extends BaseAgentSession, STEP> {

    /**
     * Unique name used in logs, SSE events, and audit rows (e.g. "config-agent").
     */
    String name();

    /**
     * Human-readable display label for UI rendering.
     */
    default String displayName() {
        return name();
    }

    /**
     * One-line description of what this sub-agent is responsible for.
     */
    default String responsibility() {
        return "";
    }

    /**
     * High-level user goals and intents this sub-agent can fulfil.
     * Exposed in metadata APIs for UI capability panels.
     */
    default List<String> intents() {
        return List.of();
    }

    /**
     * Canonical tool names this sub-agent primarily invokes.
     * Used to attribute tool_progress SSE events in the UI.
     */
    default List<String> ownedTools() {
        return List.of();
    }

    /**
     * Step/state names this sub-agent handles.
     * Allows the UI to display routing context.
     */
    default List<String> handledSteps() {
        return List.of();
    }

    /**
     * Returns true if this agent should handle the current session turn.
     *
     * @param session     the current session
     * @param currentStep the current workflow step/state
     */
    boolean canHandle(S session, STEP currentStep);

    /**
     * Step-specific instructions injected as the LLM system message for this turn.
     * Return a blank string to use the base prompt unmodified.
     *
     * @param session the current session
     * @param context parsed session context map
     */
    default String systemPrompt(final S session, final Map<String, Object> context) {
        return "";
    }

    /**
     * Returns true when this sub-agent owns its own execution pipeline
     * (e.g. a remote HTTP/MCP implementation). When true, the orchestrator
     * delegates the full response to {@link #execute} instead of running
     * the local LLM machinery.
     */
    default boolean handlesExecution() {
        return false;
    }

    /**
     * Executes the agent turn. Only called when {@link #handlesExecution()} returns true.
     *
     * @param ctx execution context containing session, message, and emitter
     */
    default void execute(final AgentExecutionContext<S> ctx) {
        throw new UnsupportedOperationException(name() + " does not implement remote execution");
    }
}
