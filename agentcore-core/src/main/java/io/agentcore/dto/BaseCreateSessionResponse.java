/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base response body returned by {@code POST /sessions} — confirms that an agent
 * session was successfully created.
 *
 * <p>Carries the minimal set of fields every agent must populate when acknowledging
 * a new session.  Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, {@code @AllArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to include domain-specific fields
 * (e.g. references to a newly created downstream resource) alongside the standard
 * session metadata.
 *
 * <h3>Extending this class</h3>
 * <pre>{@code
 * @Data
 * @SuperBuilder
 * @NoArgsConstructor
 * @AllArgsConstructor
 * @EqualsAndHashCode(callSuper = true)
 * public class MyCreateSessionResponse extends BaseCreateSessionResponse {
 *     private String market;
 *     private String resourceId; // ID of domain resource created alongside the session
 * }
 * }</pre>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseCreateSessionResponse {

    /**
     * Carries the globally unique identifier assigned to the new session by the
     * framework (e.g. a UUID).  Callers must include this value in all subsequent
     * requests that target this session.  Always present.
     */
    private String sessionId;

    /**
     * Carries the initial lifecycle status of the session immediately after
     * creation.  Typically {@code "ACTIVE"} when the session starts processing
     * the opening prompt, or {@code "PAUSED"} when the first step requires human
     * confirmation before proceeding.  See {@link io.agentcore.session.SessionStatus}
     * for all possible values.
     */
    private String status;

    /**
     * Carries the name of the first workflow step the agent is currently
     * executing or waiting on (e.g. {@code "INTENT_ROUTING"}).  Useful for
     * rendering progress UI immediately after session creation.  May be
     * {@code null} if the agent has not yet advanced to a named step.
     */
    private String currentStep;

    /**
     * Carries the identifier of the agent handling this session.  Echoes back the
     * {@code agentId} from the creation request, or the default agent ID when the
     * request did not specify one.
     */
    private String agentId;

    /**
     * Carries the ISO-8601 UTC timestamp at which the session was persisted
     * (e.g. {@code "2025-05-23T14:30:00Z"}).  Always present.
     */
    private String createdAt;
}
