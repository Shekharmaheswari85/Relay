/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code GET /sessions/{sessionId}/audit} —
 * carries the ordered list of events that were recorded against an agent session.
 *
 * <p>Each entry in {@link #events} represents one discrete occurrence such as an
 * LLM call, a tool invocation, a confirmation prompt, or a session state change.
 * The event inputs and outputs are represented as structured {@code Map} objects;
 * the serialised-string variant lives in {@link AuditTrailResponseDTO}.
 *
 * <p>Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to add aggregated metrics or
 * domain-specific summary fields.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseAuditTrailResponse {

    /**
     * Carries the identifier of the session whose audit trail is being returned.
     * Always present.
     */
    private String sessionId;

    /**
     * Carries the total number of events in {@link #events}.  Use this value to
     * drive pagination controls without inspecting the list itself.
     * Always present.
     */
    private int totalEvents;

    /**
     * Carries the ordered list of audit events, from oldest to newest.
     * Never {@code null} — an empty list is returned when the session has no
     * recorded events.
     */
    private List<AuditEvent> events;

    /**
     * Represents one discrete event recorded during session execution.  Instances
     * are created by {@code BaseAuditAdvisor} and persisted in the audit log table.
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEvent {

        /**
         * Carries the database-assigned surrogate key for this event row.
         * Useful for stable cursor-based pagination.
         */
        private Long id;

        /**
         * Carries the event-type discriminator string (e.g. {@code "TOOL_CALL"},
         * {@code "LLM_CALL"}, {@code "CONFIRMATION"}, {@code "SESSION_TRANSITION"}).
         * Use this field when filtering the audit trail via the {@code eventType}
         * query parameter.
         */
        private String eventType;

        /**
         * Carries the canonical {@code @Tool(name = ...)} value of the tool that
         * was invoked, when {@link #eventType} is {@code "TOOL_CALL"}.
         * {@code null} for non-tool events such as LLM calls.
         */
        private String toolName;

        /**
         * Carries the name of the sub-agent that recorded this event.  Populated
         * for orchestrated agents where multiple sub-agents participate in a single
         * session.  May be {@code null} for single-agent sessions.
         */
        private String agentName;

        /**
         * Carries the structured input arguments that were passed to the tool or
         * LLM at invocation time.  Represented as a map for structured querying and
         * display without requiring re-parsing.  May be {@code null} when the
         * invocation had no input.
         */
        private Map<String, Object> input;

        /**
         * Carries the structured output returned by the tool or LLM.  May be
         * {@code null} when the invocation produced no output or when the event
         * was recorded before the call completed (e.g. on error).
         */
        private Map<String, Object> output;

        /**
         * Carries the wall-clock execution time of the tool or LLM call in
         * milliseconds.  May be {@code null} for events that do not have a
         * measurable duration (e.g. session state transitions).
         */
        private Integer durationMs;

        /**
         * Carries the ISO-8601 UTC timestamp at which this event was persisted
         * (e.g. {@code "2025-05-23T14:30:01Z"}).  Always present.
         */
        private String createdAt;
    }
}
