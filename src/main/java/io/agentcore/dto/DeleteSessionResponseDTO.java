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
     * Carries the {@link io.agentcore.constants.SessionStatus} string of the session
     * at the moment of deletion (e.g. {@code "ACTIVE"}, {@code "PAUSED"}).
     * May be {@code null} when the session was not found and therefore had no
     * recorded status.
     */
    private String status;
}
