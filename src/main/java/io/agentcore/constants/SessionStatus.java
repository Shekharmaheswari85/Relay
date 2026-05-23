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
package io.agentcore.constants;

/**
 * Defines the complete set of lifecycle statuses an agent session can occupy.
 *
 * <p>Sessions advance through these statuses in response to agent execution events,
 * user interactions, and system timeouts.  The allowed transitions are:
 * <pre>
 *   (created) ──► ACTIVE ──► PAUSED ──► ACTIVE  (resume)
 *                   │                    │
 *                   │                    └──► FAILED ──► ACTIVE  (resume)
 *                   │
 *                   ├──► COMPLETED
 *                   ├──► FAILED
 *                   └──► EXPIRED
 * </pre>
 *
 * <p>Use {@link #isTerminal()} to guard against state writes on sessions that can
 * no longer be modified, {@link #allowsMessages()} before forwarding a user message
 * into the agent pipeline, and {@link #canResume()} before issuing a resume request.
 *
 * <p>Teams that need domain-specific intermediate statuses (e.g.
 * {@code AWAITING_APPROVAL}) may define their own enum and persist the raw string
 * value alongside this enum for cross-system compatibility.
 */
public enum SessionStatus {

    /**
     * Marks the session as currently running — the agent pipeline is processing
     * messages or executing tools.  The session accepts new messages and produces
     * SSE events.  Transitions to {@code PAUSED} when a confirmation gate is hit,
     * to {@code COMPLETED} when the workflow finishes, to {@code FAILED} on an
     * unrecoverable error, or to {@code EXPIRED} after the inactivity timeout.
     */
    ACTIVE,

    /**
     * Marks the session as suspended and waiting for a human actor to respond to
     * a confirmation prompt or supply missing context.  The agent pipeline is idle;
     * no tool calls or LLM calls are made while in this state.  Transitions back
     * to {@code ACTIVE} when the user sends a confirmation message via
     * {@code POST /sessions/{sessionId}/messages}, or to {@code FAILED} if the
     * session is forcibly terminated.
     */
    PAUSED,

    /**
     * Marks the session as having finished its entire workflow successfully.  All
     * planned steps have been executed and their results are available via the
     * session status endpoint.  Terminal — no further messages or state changes
     * are accepted.
     */
    COMPLETED,

    /**
     * Marks the session as having terminated due to an unrecoverable error during
     * tool execution, LLM interaction, or workflow coordination.  The error details
     * are available in the session context and the audit trail.  Non-terminal from
     * a resume perspective — callers may attempt
     * {@code POST /sessions/{sessionId}/resume} to restart from the last checkpoint.
     */
    FAILED,

    /**
     * Marks the session as having been automatically closed by the session expiry
     * scheduler after it remained inactive beyond the configured TTL.  Terminal —
     * the session cannot be resumed; a new session must be created.
     */
    EXPIRED;

    /**
     * Returns {@code true} if no further state transitions or message deliveries
     * are permitted for a session in this status.  Terminal statuses are
     * {@code COMPLETED}, {@code FAILED}, and {@code EXPIRED}.
     *
     * @return {@code true} for terminal statuses, {@code false} otherwise
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == EXPIRED;
    }

    /**
     * Returns {@code true} if a session in this status accepts new messages
     * delivered via {@code POST /sessions/{sessionId}/messages}.  Only
     * {@code ACTIVE} sessions process incoming messages; all other statuses reject
     * them with an appropriate error.
     *
     * @return {@code true} only for {@code ACTIVE}
     */
    public boolean allowsMessages() {
        return this == ACTIVE;
    }

    /**
     * Returns {@code true} if a session in this status can be restarted from its
     * last checkpoint via {@code POST /sessions/{sessionId}/resume}.  Both
     * {@code PAUSED} (awaiting confirmation) and {@code FAILED} (error recovery)
     * sessions support resume; all other statuses do not.
     *
     * @return {@code true} for {@code PAUSED} and {@code FAILED}
     */
    public boolean canResume() {
        return this == PAUSED || this == FAILED;
    }
}
