/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

/**
 * Request body for the {@code POST {basePath}/sessions} endpoint on a remote agent.
 *
 * @param agentId   the agent ID to pass to the remote agent; empty string when not applicable
 * @param createdBy the caller identity creating the session (e.g. {@code "a2a-client"})
 * @see AgentClient#createSession(String, String)
 */
public record CreateSessionRequest(String agentId, String createdBy) {}
