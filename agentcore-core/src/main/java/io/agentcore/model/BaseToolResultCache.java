/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.model;

import java.time.LocalDateTime;

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
 * Abstract {@code @MappedSuperclass} that defines the persistence schema for a single
 * cached tool-execution result.
 *
 * <p>When an agent executes an idempotent tool (e.g. a BigQuery lookup, an external API
 * read), the framework writes the result here so that a subsequent LLM retry or session
 * resume can replay the result without re-running the tool.  This cuts latency, reduces
 * external API cost, and ensures deterministic replay behaviour.
 *
 * <p>Each cache row is uniquely identified by the combination of {@link #sessionId} and
 * {@link #cacheKey}.  The cache key encodes the tool name and a hash of its input
 * arguments (format: {@code toolName::inputHash}) so that the same tool called with
 * different inputs produces independent cache entries.
 *
 * <p>Consumer modules that need domain-specific columns (e.g. a TTL field) should create
 * a concrete {@code @Entity} subclass.  The framework itself ships a ready-to-use
 * concrete class, {@link AgentToolResultCacheDO}, for agents that do not need extension.
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "my_tool_cache")
 * public class MyToolResultCacheDO extends BaseToolResultCache {
 *
 *     @Column(name = "expires_at")
 *     private LocalDateTime expiresAt;
 * }
 * }</pre>
 *
 * <p>Timestamps ({@code created_at}, {@code updated_at}) are managed automatically by the
 * JPA lifecycle callbacks {@link #onCreate()} and {@link #onUpdate()}.
 */
@MappedSuperclass
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public abstract class BaseToolResultCache {

    protected static final int SESSION_ID_LENGTH = 64;
    protected static final int CACHE_KEY_LENGTH = 255;

    /**
     * Surrogate primary key, auto-incremented by the database.
     * Prefer looking up entries by the ({@code sessionId}, {@code cacheKey}) unique pair.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The session this cache entry belongs to.
     * <p>
     * Matches {@code BaseAgentSession#sessionId}.  All cache entries for a session
     * are deleted together when the session is cleaned up.
     * Maximum length is {@value #SESSION_ID_LENGTH} characters.
     * Maps to column {@code session_id} (not-null).
     */
    @Column(name = "session_id", nullable = false, length = SESSION_ID_LENGTH)
    private String sessionId;

    /**
     * Lookup key that uniquely identifies a tool invocation within this session.
     * <p>
     * Constructed by the framework as {@code toolName::inputHash}, where
     * {@code inputHash} is a deterministic hash (e.g. SHA-256 hex) of the
     * JSON-serialised tool arguments.  Two calls to the same tool with identical
     * inputs therefore share one cache entry, while the same tool called with
     * different inputs produces separate entries.
     * Maximum length is {@value #CACHE_KEY_LENGTH} characters.
     * Maps to column {@code cache_key} (not-null).
     */
    @Column(name = "cache_key", nullable = false, length = CACHE_KEY_LENGTH)
    private String cacheKey;

    /**
     * JSON-serialised result returned by the tool on its first (real) execution.
     * <p>
     * On a cache hit the framework deserialises this value and returns it to the
     * LLM without re-invoking the tool.  The exact schema is determined by the
     * tool's return type.  {@code null} is a valid cached result when the tool
     * itself returned {@code null}.
     * Stored as a LOB to accommodate large result payloads.
     * Maps to column {@code result_json} (nullable).
     */
    @Lob
    @Column(name = "result_json")
    private String resultJson;

    /**
     * Timestamp at which this cache row was first inserted.
     * <p>
     * Set automatically by {@link #onCreate()}; never updated thereafter.
     * Maps to column {@code created_at} (not-null, not-updatable).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the most recent update to this cache row.
     * <p>
     * Refreshed by both {@link #onCreate()} and {@link #onUpdate()}.
     * Subclasses may use this alongside a TTL column to implement expiry logic.
     * Maps to column {@code updated_at} (not-null).
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA lifecycle callback invoked before the entity is first persisted.
     * <p>
     * Initialises both {@code createdAt} and {@code updatedAt} to the current time.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * JPA lifecycle callback invoked before every subsequent update.
     * <p>
     * Refreshes {@code updatedAt} to the current time.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
