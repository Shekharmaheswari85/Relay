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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code DELETE /sessions} — summarises the outcome
 * of a bulk session deletion request.
 *
 * <p>Provides a full breakdown of the requested IDs so that callers can distinguish
 * sessions that were deleted from those that were skipped or not found, without
 * needing to issue individual status queries.
 *
 * <p>Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to attach domain-specific summary
 * fields if required.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseBulkDeleteResponse {

    /**
     * Carries the total number of session IDs that were submitted in the delete
     * request body.  Equals {@code deletedSessionIds.size() +
     * skippedCompletedSessionIds.size() + notFoundSessionIds.size()}.
     * Always present.
     */
    private int requested;

    /**
     * Carries the count of sessions that were successfully removed from persistent
     * storage.  Equals {@code deletedSessionIds.size()}.  Always present.
     */
    private int deleted;

    /**
     * Carries the identifiers of every session that was successfully deleted
     * during this operation.  Never {@code null} — an empty list is returned
     * when no sessions were deleted.
     */
    private List<String> deletedSessionIds;

    /**
     * Carries the identifiers of sessions that were skipped because they had
     * already reached a terminal status ({@code COMPLETED}, {@code FAILED}, or
     * {@code EXPIRED}) and the framework is configured to protect terminal
     * sessions from deletion.  Never {@code null}.
     */
    private List<String> skippedCompletedSessionIds;

    /**
     * Carries the identifiers that did not match any persisted session record.
     * Callers should treat these as already-absent and not retry.
     * Never {@code null}.
     */
    private List<String> notFoundSessionIds;

    /**
     * Carries an optional human-readable summary of the operation outcome
     * (e.g. {@code "3 of 5 sessions deleted; 1 skipped (completed); 1 not found"}).
     * Optional — may be {@code null}.
     */
    private String message;
}
