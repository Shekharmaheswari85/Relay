/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.agent;

import java.util.Map;

import io.relay.model.BaseAgentSession;
import io.relay.stream.PipelineEmitter;

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
