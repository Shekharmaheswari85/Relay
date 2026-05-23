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
 * Concrete response body returned by {@code POST /sessions/{sessionId}/resume} in
 * default {@link io.agentcore.executor.AgentExecutor} implementations.
 *
 * <p>Extends {@link BaseResumeSessionResponse} and shadows its
 * {@code resumedFromCheckpoint} field at the DTO layer so that subclasses of this
 * type can override checkpoint-label formatting without modifying the base class.
 * In practice, callers should read {@code resumedFromCheckpoint} from this type and
 * ignore the inherited field.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ResumeSessionResponseDTO extends BaseResumeSessionResponse {

    /**
     * Carries the name or key of the checkpoint from which the session was
     * restarted (e.g. a step name such as {@code "CONFIRMATION_GATE"} or a UUID
     * checkpoint key).  Shadows the same field on {@link BaseResumeSessionResponse};
     * this value takes precedence during serialisation.  May be {@code null} when
     * the session resumed from the beginning.
     */
    private String resumedFromCheckpoint;
}
