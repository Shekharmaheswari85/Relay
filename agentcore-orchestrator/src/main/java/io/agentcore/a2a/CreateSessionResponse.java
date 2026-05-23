/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

/**
 * Response body from the {@code POST {basePath}/sessions} endpoint on a remote agent.
 *
 * @param sessionId the newly created session identifier; never {@code null} in a valid response
 * @see AgentClient#createSession(String, String)
 */
public record CreateSessionResponse(String sessionId) {}
