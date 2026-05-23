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
