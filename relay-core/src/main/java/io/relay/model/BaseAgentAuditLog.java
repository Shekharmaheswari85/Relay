/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Abstract {@code @MappedSuperclass} that defines the persistence schema for a single
 * auditable event that occurred inside an agent session.
 *
 * <p>Every meaningful action taken by an agent — an LLM call, a tool execution, a
 * workflow state transition, or a recoverable error — should produce one row in the
 * concrete subclass table.  The log is the primary source of truth for post-hoc
 * debugging, compliance reporting, and cost attribution.
 *
 * <p>Consumer modules create a concrete {@code @Entity} subclass and add any
 * domain-specific columns (e.g. {@code correlationId}, {@code userId}).
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "my_agent_audit_log")
 * public class MyAgentAuditLogDO extends BaseAgentAuditLog {
 *
 *     @Column(name = "correlation_id")
 *     private String correlationId;
 * }
 * }</pre>
 *
 * <p>{@code createdAt} is set automatically by the JPA {@link #onCreate()} lifecycle
 * callback and must not be populated by callers.
 * <p>This class implements {@link BaseAuditLog} so that framework components can query
 * audit data through a stable interface regardless of which concrete entity is in use.
 */
@MappedSuperclass
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseAgentAuditLog implements BaseAuditLog {

    protected static final int SESSION_ID_LENGTH = 64;
    protected static final int EVENT_TYPE_LENGTH = 20;
    protected static final int TOOL_NAME_LENGTH = 100;
    protected static final int AGENT_NAME_LENGTH = 64;

    /**
     * Surrogate primary key, auto-incremented by the database.
     * Application code should not use this value for cross-service references;
     * prefer querying by {@link #sessionId} and {@link #eventType}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The session this event belongs to.
     * <p>
     * Matches {@code BaseAgentSession#sessionId}; stored denormalized here so that
     * audit queries can run without joining the session table.
     * Maximum length is {@value #SESSION_ID_LENGTH} characters.
     * Maps to column {@code session_id} (not-null).
     */
    @Column(name = "session_id", nullable = false, length = SESSION_ID_LENGTH)
    private String sessionId;

    /**
     * Classifier that identifies what kind of action produced this log entry.
     * <p>
     * Standard values defined by the framework:
     * <ul>
     *   <li>{@code TOOL_CALL} — a Spring AI tool function was invoked</li>
     *   <li>{@code LLM_CALL} — a request was sent to the language model</li>
     *   <li>{@code STATE_CHANGE} — the session moved to a new workflow step or status</li>
     *   <li>{@code ERROR} — a recoverable or terminal error was captured</li>
     * </ul>
     * Agent implementations may introduce additional values.
     * Maximum length is {@value #EVENT_TYPE_LENGTH} characters.
     * Maps to column {@code event_type} (not-null).
     */
    @Column(name = "event_type", nullable = false, length = EVENT_TYPE_LENGTH)
    private String eventType;

    /**
     * The registered name of the tool that was executed.
     * <p>
     * Populated only when {@link #eventType} is {@code TOOL_CALL}; {@code null}
     * for {@code LLM_CALL}, {@code STATE_CHANGE}, and {@code ERROR} events.
     * Matches the Spring AI tool registration name (the value of {@code @Tool#name}
     * or the method name if no explicit name is given).
     * Maximum length is {@value #TOOL_NAME_LENGTH} characters.
     * Maps to column {@code tool_name} (nullable).
     */
    @Column(name = "tool_name", length = TOOL_NAME_LENGTH)
    private String toolName;

    /**
     * Bean name of the {@code BaseSubAgent} that was active when this event occurred.
     * <p>
     * {@code null} when the event was produced by the orchestrator or a component
     * that runs outside any sub-agent context.
     * Maximum length is {@value #AGENT_NAME_LENGTH} characters.
     * Maps to column {@code agent_name} (nullable).
     */
    @Column(name = "agent_name", length = AGENT_NAME_LENGTH)
    private String agentName;

    /**
     * JSON-serialized representation of the input supplied to the tool or LLM.
     * <p>
     * For {@code TOOL_CALL} events this is the argument map passed to the tool
     * function.  For {@code LLM_CALL} events this is the prompt or message list.
     * May be {@code null} when the event has no meaningful input (e.g. a
     * {@code STATE_CHANGE}).  Stored as a LOB to accommodate large prompts.
     * Maps to column {@code input_json} (nullable).
     */
    @Lob
    @Column(name = "input_json")
    private String inputJson;

    /**
     * JSON-serialized representation of the result returned by the tool or LLM.
     * <p>
     * For {@code TOOL_CALL} events this is the tool's return value.  For
     * {@code LLM_CALL} events this is the model's response.  For {@code ERROR}
     * events this may contain the exception message and stack summary.
     * May be {@code null} when there is no output to record.
     * Stored as a LOB to accommodate large responses.
     * Maps to column {@code output_json} (nullable).
     */
    @Lob
    @Column(name = "output_json")
    private String outputJson;

    /**
     * Wall-clock duration of the operation in milliseconds.
     * <p>
     * Covers the time from when the framework dispatched the call to when it
     * received the result.  {@code null} if timing was not measured (e.g. for
     * {@code STATE_CHANGE} events).
     * Maps to column {@code duration_ms} (nullable).
     */
    @Column(name = "duration_ms")
    private Integer durationMs;

    /**
     * Total token count consumed by an LLM call (prompt tokens + completion tokens).
     * <p>
     * Populated only when {@link #eventType} is {@code LLM_CALL}; {@code null}
     * for all other event types.  Used for cost attribution and quota enforcement.
     * Maps to column {@code token_count} (nullable).
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * Timestamp at which this audit entry was inserted.
     * <p>
     * Set automatically by {@link #onCreate()}; never updated thereafter.
     * The ordering of records by this column reconstructs the exact event
     * timeline for a session.
     * Maps to column {@code created_at} (not-null, not-updatable).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback invoked before the entity is first persisted.
     * <p>
     * Sets {@code createdAt} to the current time.  Callers must not set
     * {@code createdAt} themselves; doing so will be overwritten by this callback.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
