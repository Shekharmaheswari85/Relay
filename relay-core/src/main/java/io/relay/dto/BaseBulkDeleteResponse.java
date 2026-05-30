/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

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
