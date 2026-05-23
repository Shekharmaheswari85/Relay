/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.session;

/**
 * Defines the lifecycle states an agent session passes through from creation
 * to final disposition.
 *
 * <p>Transitions follow a directed graph: a session starts as {@link #ACTIVE},
 * may be temporarily halted as {@link #PAUSED}, may enter {@link #FAILED} after
 * an unrecoverable turn error, and eventually reaches one of the terminal states:
 * {@link #COMPLETED} or {@link #EXPIRED}. Terminal sessions are immutable; no
 * further messages are accepted and no state transitions are permitted.
 *
 * <p>Use {@link #isTerminal()} as a guard before routing a new user message:
 * <pre>{@code
 * if (session.getStatusEnum().isTerminal()) {
 *     throw new SessionClosedException(session.getSessionId());
 * }
 * }</pre>
 *
 * <p>Use {@link #canResume()} to determine whether a paused or failed session may
 * be re-activated:
 * <pre>{@code
 * if (session.getStatusEnum().canResume()) {
 *     session.setStatus(SessionStatus.ACTIVE.name());
 * }
 * }</pre>
 *
 * <p>The {@code io.agentcore.scheduler.BaseSessionExpiryScheduler} transitions
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
     * The agent encountered an unrecoverable error. The session does not accept
     * new messages until it is resumed from its last checkpoint.
     */
    FAILED,

    /**
     * The session was closed by the {@code io.agentcore.scheduler.BaseSessionExpiryScheduler}
     * because no activity was recorded within the configured idle window.
     * This is a terminal state; no further messages are accepted.
     */
    EXPIRED;

    /**
     * Returns {@code true} when this status represents a terminal state from
     * which the session cannot transition further.
     *
     * <p>Terminal statuses are {@link #COMPLETED} and {@link #EXPIRED}. Use this
     * as a guard before routing new user messages or modifying session state.
     *
     * @return {@code true} for {@code COMPLETED} and {@code EXPIRED};
     *         {@code false} otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == EXPIRED;
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
     * Returns {@code true} when the session may be transitioned back to
     * {@link #ACTIVE}.
     *
     * @return {@code true} for {@code PAUSED} and {@code FAILED}
     */
    public boolean canResume() {
        return this == PAUSED || this == FAILED;
    }

    /**
     * Returns {@code true} if a session in this status accepts user messages.
     *
     * @return {@code true} only for {@code ACTIVE}
     */
    public boolean allowsMessages() {
        return this == ACTIVE;
    }
}
