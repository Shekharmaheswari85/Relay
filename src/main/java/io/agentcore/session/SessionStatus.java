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
package io.agentcore.session;

/**
 * Defines the lifecycle states an agent session passes through from creation
 * to final disposition.
 *
 * <p>Transitions follow a directed graph: a session starts as {@link #ACTIVE},
 * may be temporarily halted as {@link #PAUSED}, and eventually reaches one of
 * the three terminal states — {@link #COMPLETED}, {@link #FAILED}, or
 * {@link #EXPIRED}. Terminal sessions are immutable; no further messages are
 * accepted and no state transitions are permitted.
 *
 * <p>Use {@link #isTerminal()} as a guard before routing a new user message:
 * <pre>{@code
 * if (session.getStatusEnum().isTerminal()) {
 *     throw new SessionClosedException(session.getSessionId());
 * }
 * }</pre>
 *
 * <p>Use {@link #canResume()} to determine whether a paused session may be
 * re-activated:
 * <pre>{@code
 * if (session.getStatusEnum().canResume()) {
 *     session.setStatus(SessionStatus.ACTIVE.name());
 * }
 * }</pre>
 *
 * <p>The {@link io.agentcore.scheduler.BaseSessionExpiryScheduler} transitions
 * idle {@link #ACTIVE} and {@link #PAUSED} sessions to {@link #EXPIRED}
 * on a configurable schedule.
 */
public enum SessionStatus {

    /**
     * The session is accepting new messages and the agent pipeline is either
     * processing a request or waiting for the next user input.
     */
    ACTIVE,

    /**
     * The session has been temporarily halted, for example while waiting for
     * out-of-band confirmation or an async external event. The session can be
     * resumed by transitioning it back to {@link #ACTIVE}.
     */
    PAUSED,

    /**
     * The agent fulfilled the user's goal and the conversation ended normally.
     * This is a terminal state; no further messages are accepted.
     */
    COMPLETED,

    /**
     * The agent encountered an unrecoverable error and the session was abandoned.
     * This is a terminal state; no further messages are accepted.
     */
    FAILED,

    /**
     * The session was closed by the {@link io.agentcore.scheduler.BaseSessionExpiryScheduler}
     * because no activity was recorded within the configured idle window.
     * This is a terminal state; no further messages are accepted.
     */
    EXPIRED;

    /**
     * Returns {@code true} when this status represents a terminal state from
     * which the session cannot transition further.
     *
     * <p>Terminal statuses are {@link #COMPLETED}, {@link #FAILED}, and
     * {@link #EXPIRED}. Use this as a guard before routing new user messages
     * or modifying session state.
     *
     * @return {@code true} for {@code COMPLETED}, {@code FAILED}, and
     *         {@code EXPIRED}; {@code false} otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED;
    }

    /**
     * Returns {@code true} when the session is in the {@link #ACTIVE} state and
     * can accept new user messages.
     *
     * @return {@code true} only for {@code ACTIVE}
     */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * Returns {@code true} when the session is {@link #PAUSED} and may be
     * transitioned back to {@link #ACTIVE}.
     *
     * @return {@code true} only for {@code PAUSED}
     */
    public boolean canResume() {
        return this == PAUSED;
    }
}
