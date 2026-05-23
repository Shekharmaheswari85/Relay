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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code DELETE /sessions/{sessionId}} — reports the
 * outcome of a single-session deletion request.
 *
 * <p>Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to add domain-specific fields.
 * The framework-provided concrete type is {@link DeleteSessionResponseDTO}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseDeleteSessionResponse {

    /**
     * Carries the identifier of the session that was targeted by the delete
     * request.  Echoes back the path variable so callers can correlate
     * asynchronous responses.  Always present.
     */
    private String sessionId;

    /**
     * Indicates whether the session was actually removed from persistent storage.
     * {@code true} when deletion succeeded; {@code false} when the session was
     * not found or was already in a terminal state that prevents deletion.
     */
    private boolean deleted;

    /**
     * Carries a human-readable explanation of why the deletion succeeded or was
     * rejected (e.g. {@code "Session not found"} or
     * {@code "Active session deleted and chat history purged"}).  Optional —
     * may be {@code null} when {@link #deleted} is {@code true} and no additional
     * context is needed.
     */
    private String message;
}
