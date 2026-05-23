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
package io.agentcore.model;

import java.time.LocalDateTime;

/**
 * Structural contract for every audit log entity in the framework.
 *
 * <p>Framework components that read or aggregate audit data (the observability service,
 * audit-trail REST endpoints, cleanup schedulers) depend on this interface rather than
 * on a concrete entity class.  This decouples them from the JPA hierarchy and allows
 * consumer modules to introduce their own entity columns without breaking shared logic.
 *
 * <p>All concrete implementations are expected to be {@code @MappedSuperclass} or
 * {@code @Entity} classes that also carry the persistence annotations; this interface
 * purely exposes the read side.
 *
 * @see BaseAgentAuditLog
 */
public interface BaseAuditLog {

    /**
     * Returns the surrogate database primary key of this audit entry.
     *
     * @return the auto-generated numeric identifier, or {@code null} if the entity
     *         has not yet been persisted
     */
    Long getId();

    /**
     * Returns the session this event belongs to.
     * <p>
     * Matches {@code BaseAgentSession#sessionId}.
     *
     * @return the non-null session identifier string
     */
    String getSessionId();

    /**
     * Returns the classifier that describes what kind of action was audited.
     * <p>
     * Standard values are {@code TOOL_CALL}, {@code LLM_CALL}, {@code STATE_CHANGE},
     * and {@code ERROR}; agent implementations may define additional values.
     *
     * @return the non-null event type string
     */
    String getEventType();

    /**
     * Returns the name of the tool that was invoked, if applicable.
     * <p>
     * Non-null only when {@link #getEventType()} returns {@code TOOL_CALL}.
     *
     * @return the tool name, or {@code null} for non-tool events
     */
    String getToolName();

    /**
     * Returns the name of the sub-agent that was active when this event occurred.
     * <p>
     * {@code null} when the event originated outside any sub-agent context.
     *
     * @return the sub-agent bean name, or {@code null}
     */
    String getAgentName();

    /**
     * Returns the wall-clock duration of the audited operation in milliseconds.
     * <p>
     * {@code null} when timing was not measured (e.g. for {@code STATE_CHANGE} entries).
     *
     * @return the duration in milliseconds, or {@code null}
     */
    Integer getDurationMs();

    /**
     * Returns the timestamp at which this audit record was written.
     * <p>
     * Records within a session ordered by this value reconstruct the exact
     * event timeline.
     *
     * @return the non-null creation timestamp
     */
    LocalDateTime getCreatedAt();
}
