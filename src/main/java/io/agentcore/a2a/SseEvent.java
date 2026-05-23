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
package io.agentcore.a2a;

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
