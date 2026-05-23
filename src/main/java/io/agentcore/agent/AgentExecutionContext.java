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

import java.util.Map;

import io.agentcore.model.BaseAgentSession;
import io.agentcore.stream.PipelineEmitter;

/**
 * Execution context passed to sub-agents when they handle their own execution pipeline.
 * <p>
 * Contains all inputs needed to produce the SSE response:
 * <ul>
 *   <li>Session entity with current state</li>
 *   <li>Session identifier for logging and tracking</li>
 *   <li>User message</li>
 *   <li>Emitter for writing SSE events to the client</li>
 *   <li>Parsed session context map</li>
 * </ul>
 *
 * @param <S>       the session entity type
 * @param session   the current session entity
 * @param sessionId session identifier for logging and tracking
 * @param message   the user's message
 * @param emitter   the pipeline emitter for writing SSE events to the client
 * @param context   parsed session context map
 */
public record AgentExecutionContext<S extends BaseAgentSession>(
        S session,
        String sessionId,
        String message,
        PipelineEmitter emitter,
        Map<String, Object> context) {}
