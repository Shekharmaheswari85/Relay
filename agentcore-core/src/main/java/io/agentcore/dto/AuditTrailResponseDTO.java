/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Concrete audit trail response returned by {@code GET /sessions/{sessionId}/audit}
 * in default {@link io.agentcore.executor.AgentExecutor} implementations.
 *
 * <p>Extends {@link BaseAuditTrailResponse} and adds {@link #auditEvents}, a parallel
 * list of events whose {@code input} and {@code output} fields are pre-serialised to
 * JSON strings rather than structured maps.  This representation simplifies
 * transport and avoids schema drift when the tool-argument shapes evolve independently
 * of the audit API.
 *
 * <p>Consumers that need structured map access should use the {@link AuditEvent}
 * entries from the inherited {@code events} field in {@link BaseAuditTrailResponse}.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AuditTrailResponseDTO extends BaseAuditTrailResponse {

    /**
     * Carries the list of audit events with JSON-string-serialised inputs and outputs.
     * Populated by the default executor; may be {@code null} when an executor
     * populates the inherited {@code events} field instead.
     */
    private List<AuditEventDTO> auditEvents;

    /**
     * Represents one discrete event recorded during session execution, with
     * {@link #input} and {@link #output} pre-serialised as JSON strings for
     * transport compactness.
     */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditEventDTO {

        /**
         * Carries the database-assigned surrogate key for this event row.
         * Useful for stable cursor-based pagination.
         */
        private Long id;

        /**
         * Carries the event-type discriminator string (e.g. {@code "TOOL_CALL"},
         * {@code "LLM_CALL"}, {@code "CONFIRMATION"}, {@code "SESSION_TRANSITION"}).
         */
        private String eventType;

        /**
         * Carries the canonical {@code @Tool(name = ...)} value of the tool that
         * was invoked, when {@link #eventType} is {@code "TOOL_CALL"}.
         * {@code null} for non-tool events.
         */
        private String toolName;

        /**
         * Carries the name of the sub-agent that recorded this event.  May be
         * {@code null} for single-agent sessions.
         */
        private String agentName;

        /**
         * Carries the JSON-serialised string representation of the tool or LLM
         * invocation inputs.  Use {@code ObjectMapper.readValue(input, Map.class)}
         * to obtain a structured view.  May be {@code null}.
         */
        private String input;

        /**
         * Carries the JSON-serialised string representation of the tool or LLM
         * output.  May be {@code null} when the invocation produced no output or
         * failed before returning.
         */
        private String output;

        /**
         * Carries the wall-clock execution time of the tool or LLM call in
         * milliseconds.  May be {@code null} for state-transition events.
         */
        private Integer durationMs;

        /**
         * Carries the number of LLM tokens consumed by this event, when
         * {@link #eventType} is {@code "LLM_CALL"}.  {@code null} for non-LLM
         * events or when token counting is disabled.
         */
        private Integer tokenCount;

        /**
         * Carries the ISO-8601 UTC timestamp at which this event was persisted
         * (e.g. {@code "2025-05-23T14:30:01Z"}).  Always present.
         */
        private String createdAt;
    }
}
