/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

/**
 * Immutable representation of a single Server-Sent Event received from a remote agent.
 *
 * <p>Maps directly to the SSE wire format:
 * <pre>
 * event: message
 * data: {"text":"Hello"}
 * </pre>
 *
 * <p>The {@link #event()} field corresponds to the {@code event:} line (defaults to
 * {@code "message"} when not specified by the server). The {@link #data()} field holds the
 * concatenated {@code data:} lines for a single SSE dispatch block.
 *
 * @param event the SSE event name; never {@code null}
 * @param data  the SSE payload; never {@code null}
 * @see AgentClient#sendMessage(String, String, java.util.function.Consumer)
 */
public record SseEvent(String event, String data) {}
