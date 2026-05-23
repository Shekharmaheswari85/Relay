/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Concrete response body returned by {@code DELETE /sessions/{sessionId}} in default
 * {@link io.agentcore.executor.AgentExecutor} implementations.
 *
 * <p>Extends {@link BaseDeleteSessionResponse} with a {@link #status} field that
 * carries the lifecycle state the session was in at the moment it was deleted
 * (e.g. {@code "ACTIVE"}, {@code "PAUSED"}).  Callers can use this to confirm that
 * an in-flight session was interrupted rather than already completed.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class DeleteSessionResponseDTO extends BaseDeleteSessionResponse {

    /**
     * Carries the {@link io.agentcore.session.SessionStatus} string of the session
     * at the moment of deletion (e.g. {@code "ACTIVE"}, {@code "PAUSED"}).
     * May be {@code null} when the session was not found and therefore had no
     * recorded status.
     */
    private String status;
}
