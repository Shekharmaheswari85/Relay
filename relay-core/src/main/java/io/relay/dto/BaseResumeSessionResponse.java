/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code POST /sessions/{sessionId}/resume} —
 * confirms that a paused or failed session has been restarted from its last
 * checkpoint.
 *
 * <p>Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to include domain-specific fields.
 * The framework-provided concrete type is {@link ResumeSessionResponseDTO}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseResumeSessionResponse {

    /**
     * Carries the identifier of the session that was resumed.  Echoes back the
     * path variable so callers can correlate asynchronous responses.
     * Always present.
     */
    private String sessionId;

    /**
     * Carries the lifecycle status of the session immediately after the resume
     * transition.  Typically {@code "ACTIVE"} on success.  See
     * {@link io.relay.session.SessionStatus} for all possible values.
     */
    private String status;

    /**
     * Carries the name or identifier of the checkpoint from which the session
     * execution was restarted (e.g. a step name such as {@code "CONFIRMATION_GATE"}
     * or a UUID checkpoint key).  May be {@code null} when the session resumed
     * from the beginning rather than from a saved checkpoint.
     */
    private String resumedFromCheckpoint;

    /**
     * Carries the workflow step the agent is currently executing after the resume
     * (e.g. {@code "EXECUTING_TOOL"}).  Useful for immediately refreshing
     * progress UI without issuing a separate status query.  May be {@code null}
     * if the agent has not yet advanced to a named step.
     */
    private String currentStep;

    /**
     * Indicates whether the session was actually transitioned back to an active
     * state.  {@code true} on success; {@code false} when the session was not
     * found, was already active, or was in a non-resumable terminal state.
     */
    private boolean resumed;

    /**
     * Carries a human-readable explanation when {@link #resumed} is {@code false}
     * (e.g. {@code "Session is already ACTIVE"} or
     * {@code "Session has reached a terminal state and cannot be resumed"}).
     * Optional — may be {@code null} on success.
     */
    private String message;
}
