/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

/**
 * Request body for the {@code POST {basePath}/sessions/{id}/messages} endpoint on a remote agent.
 *
 * @param content the user message to send; empty string when not provided
 * @see AgentClient#sendMessage(String, String, java.util.function.Consumer)
 */
public record SendMessageRequest(String content) {}
