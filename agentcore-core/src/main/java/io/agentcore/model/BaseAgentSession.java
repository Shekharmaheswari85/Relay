/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.model;

import java.time.LocalDateTime;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Abstract {@code @MappedSuperclass} that defines the common persistence schema for every
 * agent session in the framework.
 *
 * <p>A <em>session</em> is the unit of work for a single user interaction with an agent.
 * It carries the full lifecycle state (status, current workflow step, active sub-agent),
 * the serialised conversation context, and the audit timestamps needed for expiry,
 * resumption, and multi-tenant isolation.
 *
 * <p>Consumer modules must create a concrete {@code @Entity} subclass and add any
 * domain-specific columns (e.g. {@code ticketId}, {@code customerId}, {@code market}).
 * The subclass is also responsible for choosing the physical table name.
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "my_agent_sessions")
 * public class MyAgentSessionDO extends BaseAgentSession {
 *
 *     @Column(name = "ticket_id")
 *     private String ticketId;
 *
 *     @Override
 *     public Map<String, Object> getDomainContext() {
 *         return Map.of("ticketId", ticketId);
 *     }
 * }
 * }</pre>
 *
 * <p>All timestamp columns ({@code created_at}, {@code updated_at}) are managed
 * automatically by the JPA lifecycle callbacks {@link #onCreate()} and
 * {@link #onUpdate()}; callers must not set them manually.
 */
@MappedSuperclass
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseAgentSession {

    protected static final int SESSION_ID_LENGTH = 64;
    protected static final int AGENT_ID_LENGTH = 64;
    protected static final int STEP_LENGTH = 50;
    protected static final int STATUS_LENGTH = 20;
    protected static final int USER_ID_LENGTH = 100;
    protected static final int TENANT_ID_LENGTH = 100;

    /**
     * Surrogate primary key, auto-incremented by the database.
     * Use {@link #sessionId} to identify a session in application code;
     * this key exists solely for JPA join efficiency.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Externally visible, application-level session identifier.
     * <p>
     * This is the value exposed in API responses and passed between services.
     * It must be globally unique — the framework generates it at session creation
     * time (e.g., a UUID or a prefixed random token such as {@code sess-abc123xyz}).
     * Maximum length is {@value #SESSION_ID_LENGTH} characters.
     * Maps to column {@code session_id} (unique, not-null).
     */
    @Column(name = "session_id", nullable = false, unique = true, length = SESSION_ID_LENGTH)
    private String sessionId;

    /**
     * Logical identifier for the agent type that owns this session.
     * <p>
     * Conventionally a kebab-case name such as {@code onboarding-agent} or
     * {@code support-agent}. Used to route messages to the correct
     * {@code BaseSubAgent} implementation and to partition metrics by agent.
     * Maximum length is {@value #AGENT_ID_LENGTH} characters.
     * Maps to column {@code agent_id} (not-null).
     */
    @Column(name = "agent_id", nullable = false, length = AGENT_ID_LENGTH)
    private String agentId;

    /**
     * Name of the workflow step the session is currently executing.
     * <p>
     * Typical values are {@code INIT}, {@code DISCOVERY}, {@code EXECUTION},
     * and {@code COMPLETED}, though agents may define their own step vocabulary.
     * Defaults to {@code INIT} when a new session is first persisted.
     * Maximum length is {@value #STEP_LENGTH} characters.
     * Maps to column {@code current_step} (not-null).
     */
    @Column(name = "current_step", nullable = false, length = STEP_LENGTH)
    private String currentStep;

    /**
     * High-level lifecycle status of the session.
     * <p>
     * Valid values: {@code ACTIVE} — the session is processing or awaiting input;
     * {@code PAUSED} — execution is suspended pending human confirmation;
     * {@code COMPLETED} — the agent has finished successfully;
     * {@code FAILED} — a terminal error occurred.
     * Defaults to {@code ACTIVE} when a new session is first persisted.
     * Maximum length is {@value #STATUS_LENGTH} characters.
     * Maps to column {@code status} (not-null).
     */
    @Column(name = "status", nullable = false, length = STATUS_LENGTH)
    private String status;

    /**
     * JSON-serialised snapshot of the full conversation context.
     * <p>
     * The payload typically includes the chat message history (Spring AI
     * {@code Message} list), accumulated tool results, and any intermediate
     * workflow state that must survive a JVM restart or a session resume.
     * The exact schema is defined by the agent implementation.
     * Stored as a LOB to accommodate large histories.
     * Maps to column {@code context_json} (nullable).
     */
    @Lob
    @Column(name = "context_json")
    private String contextJson;

    /**
     * Bean name of the {@code BaseSubAgent} that is currently handling this session.
     * <p>
     * Set when the orchestrator delegates to a sub-agent, and cleared (set to
     * {@code null}) once the sub-agent finishes or the session is paused.
     * Maximum length is {@value #STEP_LENGTH} characters.
     * Maps to column {@code active_sub_agent} (nullable).
     */
    @Column(name = "active_sub_agent", length = STEP_LENGTH)
    private String activeSubAgent;

    /**
     * The workflow step at which the most recent durable checkpoint was written.
     * <p>
     * When a session is resumed after a pause or failure, execution restarts from
     * this step rather than from the beginning. {@code null} indicates the session
     * has not yet reached a checkpoint.
     * Maximum length is {@value #STEP_LENGTH} characters.
     * Maps to column {@code last_checkpoint} (nullable).
     */
    @Column(name = "last_checkpoint", length = STEP_LENGTH)
    private String lastCheckpoint;

    /**
     * JSON-serialised list of tool names that are pre-approved to execute without
     * a human confirmation prompt for this specific session.
     * <p>
     * Format: a JSON array of string tool names, e.g.
     * {@code ["fetch_order", "lookup_customer"]}. When {@code null} or empty,
     * every mutation tool requires explicit confirmation.
     * Stored as a LOB.
     * Maps to column {@code auto_approve} (nullable).
     */
    @Lob
    @Column(name = "auto_approve")
    private String autoApprove;

    /**
     * Identity of the user who initiated this session.
     * <p>
     * Typically a user ID, email address, or SSO subject claim — whatever
     * the consuming application treats as the canonical user identity.
     * Used for audit trails and ownership queries.
     * Maximum length is {@value #USER_ID_LENGTH} characters.
     * Maps to column {@code created_by} (not-null).
     */
    @Column(name = "created_by", nullable = false, length = USER_ID_LENGTH)
    private String createdBy;

    /**
     * Tenant identifier for multi-tenant deployments.
     * <p>
     * Populated at session creation time by the {@code TenantResolver} component.
     * {@code null} in single-tenant deployments or when tenant resolution is
     * not configured. All repository queries that filter by tenant must include
     * this column to prevent cross-tenant data leakage.
     * Maximum length is {@value #TENANT_ID_LENGTH} characters.
     * Maps to column {@code tenant_id} (nullable).
     */
    @Column(name = "tenant_id", length = TENANT_ID_LENGTH)
    private String tenantId;

    /**
     * Timestamp at which this session row was first inserted.
     * <p>
     * Set automatically by {@link #onCreate()}; never updated thereafter.
     * Maps to column {@code created_at} (not-null, not-updatable).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the most recent write to this session row.
     * <p>
     * Refreshed automatically by both {@link #onCreate()} and {@link #onUpdate()}.
     * The expiry scheduler uses this value to identify stale sessions.
     * Maps to column {@code updated_at} (not-null).
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA lifecycle callback invoked before the entity is first persisted.
     * <p>
     * Initialises {@code createdAt} and {@code updatedAt} to the current time,
     * and applies default values for {@code currentStep} ({@code "INIT"}) and
     * {@code status} ({@code "ACTIVE"}) if those fields were not set by the caller.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (currentStep == null) {
            currentStep = "INIT";
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    /**
     * JPA lifecycle callback invoked before every subsequent update.
     * <p>
     * Refreshes {@code updatedAt} to the current time so that the expiry scheduler
     * and audit queries always reflect the true time of last modification.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Returns a map of domain-specific field values for this session.
     * <p>
     * Subclasses must override this method to expose the columns they add
     * (e.g. {@code ticketId}, {@code customerId}) so that framework components
     * such as the observability service, the audit advisor, and the metrics
     * emitter can include domain context without depending on the concrete type.
     * The returned map should contain only non-sensitive, loggable values.
     *
     * @return a non-null map of domain field names to their current values;
     *         may be empty if the subclass adds no domain fields
     */
    public abstract Map<String, Object> getDomainContext();
}
