/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base request body for {@code POST /sessions} — creates a new agent session.
 *
 * <p>Carries the minimum set of fields that every agent's session-creation endpoint
 * requires. Extend this class and annotate the subclass with {@code @Data},
 * {@code @SuperBuilder}, {@code @NoArgsConstructor}, {@code @AllArgsConstructor}, and
 * {@code @EqualsAndHashCode(callSuper = true)} to add domain-specific fields without
 * losing Lombok's builder and equality support.
 *
 * <p>Fields marked <em>optional</em> below may be omitted; the framework supplies
 * defaults when they are absent. Fields marked <em>required</em> must be non-null
 * and non-blank for the request to be accepted.
 *
 * <p>Any JSON key that does not map to a declared field is silently captured in
 * {@link #additionalFields} so that forward-compatible clients can include extra
 * attributes without causing deserialization errors.
 *
 * <h3>Extending this class</h3>
 * <pre>{@code
 * @Data
 * @SuperBuilder
 * @NoArgsConstructor
 * @AllArgsConstructor
 * @EqualsAndHashCode(callSuper = true)
 * public class MyCreateSessionRequest extends BaseCreateSessionRequest {
 *     private String market;   // required by MyAgent
 *     private String channel;  // optional — defaults to "web"
 * }
 * }</pre>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseCreateSessionRequest {

    /**
     * Carries the opening user message that seeds the agent conversation.
     * Accepts the JSON aliases {@code "content"}, {@code "message"}, {@code "query"},
     * and {@code "prompt"} so that callers using any of those key names are handled
     * transparently. Optional — if omitted, the session is created without an
     * initial message and the first message must arrive via
     * {@code POST /sessions/{sessionId}/messages}.
     */
    @JsonAlias({"content", "message", "query", "prompt"})
    private String userPrompt;

    /**
     * Carries the list of tool names or glob patterns whose confirmation prompts
     * the framework skips automatically for this session.  Each entry is matched
     * against the tool's canonical {@code @Tool(name = ...)} value.
     * Optional — when absent, all confirmation-gated tools still require explicit
     * user approval.
     */
    private List<String> autoApprove;

    /**
     * Carries the identifier of the human or service principal that initiated the
     * session (e.g. a user ID, service account name, or JWT subject claim).
     * Persisted in the session record for audit purposes. Optional.
     */
    private String createdBy;

    /**
     * Carries an LLM provider override for this session (e.g. {@code "openai"},
     * {@code "anthropic"}).  When absent, the agent uses the provider configured
     * in {@code agent.llm.provider}.  Optional.
     */
    private String provider;

    /**
     * Carries the agent ID that should handle this session.  When absent or
     * {@code null}, the framework routes the request to the single registered
     * agent, or throws if more than one agent is registered and no default is
     * configured. Optional for single-agent deployments; required when multiple
     * agents are registered.
     */
    private String agentId;

    /**
     * Carries the tenant identifier for multi-tenant deployments.  When absent
     * or blank, {@code BaseAgentController} delegates to the configured
     * {@code TenantResolver} to derive the tenant from the request headers or
     * context.  If no resolver is configured either, the session is assigned to
     * {@code TenantResolver.SINGLE_TENANT}. Optional.
     */
    private String tenantId;

    /**
     * Captures every JSON key not explicitly declared on this class or its
     * subclasses.  Populated by Jackson's {@code @JsonAnySetter} mechanism,
     * allowing clients to include forward-compatible or domain-specific fields
     * without causing deserialization errors.  Excluded from serialization via
     * {@code @JsonIgnore}.
     */
    @JsonIgnore
    private final Map<String, Object> additionalFields = new HashMap<>();

    /**
     * Records a single extra JSON field into {@link #additionalFields}.
     * Called by Jackson for every unrecognized key during deserialization.
     *
     * @param key   the unrecognized JSON field name
     * @param value the field value, which may be any JSON-compatible type
     */
    @JsonAnySetter
    public void addUnknownField(final String key, final Object value) {
        additionalFields.put(key, value);
    }

    /**
     * Returns the value of a previously captured extra field cast to the
     * inferred target type, or {@code null} if no such field was received.
     *
     * @param key the field name as it appeared in the JSON body
     * @param <T> the expected return type
     * @return the captured value, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T getAdditionalField(final String key) {
        return (T) additionalFields.get(key);
    }
}
