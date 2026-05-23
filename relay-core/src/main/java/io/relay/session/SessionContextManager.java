/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides JSON serialization, typed value extraction, and session-context
 * mutation utilities shared across all agent implementations.
 *
 * <p>Each agent session stores its mutable state as a {@code Map<String, Object>}
 * that is persisted as a JSON column. This class owns the boundary between the
 * raw JSON string and the strongly-typed in-memory map, and defines the
 * well-known keys used throughout the agent pipeline.
 *
 * <p>Extend this class to add domain-specific context key constants and
 * resolution methods:
 * <pre>{@code
 * @Component
 * public class OrderAgentContextManager extends SessionContextManager {
 *
 *     public static final String CONTEXT_KEY_ORDER_ID = "orderId";
 *
 *     public OrderAgentContextManager(ObjectMapper objectMapper) {
 *         super(objectMapper);
 *     }
 *
 *     public String resolveOrderId(Map<String, Object> context) {
 *         return asString(context.get(CONTEXT_KEY_ORDER_ID), null);
 *     }
 * }
 * }</pre>
 *
 * <p>This class is stateless beyond the injected {@link ObjectMapper} and is
 * safe for concurrent use from multiple threads.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SessionContextManager {

    // ─── Generic context key constants ───────────────────────────────────────

    /** Context key for the LLM provider name (e.g., {@code "openai"}, {@code "bedrock"}). */
    public static final String CONTEXT_KEY_PROVIDER = "provider";

    /** Context key for the agent identifier that owns this session. */
    public static final String CONTEXT_KEY_AGENT_ID = "agentId";

    /** Context key for the running count of conversation turns in this session. */
    public static final String CONTEXT_KEY_CHAT_TURNS = "chatTurns";

    /** Context key for the cumulative number of tool calls made in this session. */
    public static final String CONTEXT_KEY_TOTAL_TOOL_CALLS = "totalToolCalls";

    /**
     * Context key for the error message produced by the most recent failed tool call.
     * Written by {@link #recordToolError} and consumed by {@link #buildToolErrorInjection}
     * to inject corrective context into the next LLM prompt turn.
     */
    public static final String CONTEXT_KEY_LAST_TOOL_ERROR = "lastToolError";

    /**
     * Context key for the canonical name of the tool that last failed.
     * Written by {@link #recordToolError} alongside {@link #CONTEXT_KEY_LAST_TOOL_ERROR}.
     */
    public static final String CONTEXT_KEY_LAST_TOOL_NAME = "lastToolName";

    /**
     * Context key for the number of consecutive tool-call failures within the current
     * conversation turn. Incremented by {@link #recordToolError} and removed by
     * {@link #clearToolError} after a successful tool execution.
     */
    public static final String CONTEXT_KEY_TOOL_RETRY_COUNT = "toolRetryCount";

    /**
     * Sentinel value stored in the context map when a required field has not yet
     * been resolved. {@link #isMissingContextValue} treats this value as absent,
     * enabling the agent to detect and prompt for missing inputs.
     */
    public static final String PENDING_CONTEXT_VALUE = "__pending__";

    private final ObjectMapper objectMapper;

    // ─── JSON parsing ────────────────────────────────────────────────────────

    /**
     * Deserializes a JSON string into a mutable {@code Map<String, Object>}.
     *
     * <p>Use this to load the context column from persistence into memory at
     * the start of each agent turn.
     *
     * @param contextJson the JSON string to parse; may be {@code null} or blank
     * @return a mutable map representing the context; empty map if the input is
     *         {@code null}, blank, or not valid JSON
     */
    public Map<String, Object> parseContextJson(final String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(contextJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse context JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Serializes an object to a JSON string.
     *
     * <p>Use this to convert the in-memory context map back to a JSON string
     * before writing it to the persistence layer.
     *
     * @param payload the object to serialize; may be a {@code Map}, POJO, or collection
     * @return JSON string representation; returns {@code {"error":"serialize_failed"}}
     *         if serialization fails so callers always receive valid JSON
     */
    public String toJson(final Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize payload: {}", ex.getMessage());
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    // ─── Typed accessors ─────────────────────────────────────────────────────

    /**
     * Converts a raw context value to a trimmed string, returning {@code fallback}
     * when the value is absent or blank.
     *
     * @param value    the raw value from the context map; may be {@code null}
     * @param fallback the value to return when {@code value} is absent or blank
     * @return the trimmed string value, or {@code fallback}
     */
    public String asString(final Object value, final String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    /**
     * Casts a raw context value to an unmodifiable {@code Map<String, Object>}.
     *
     * @param value the raw value from the context map; may be {@code null}
     * @return the cast map, or an empty immutable map when {@code value} is not a
     *         {@link Map}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap(final Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return (Map<String, Object>) mapValue;
        }
        return Map.of();
    }

    /**
     * Returns a mutable copy of the raw context value as a {@code HashMap}.
     *
     * <p>Use this when the returned map must be modified (e.g., to add nested
     * context entries) before being stored back into the session context.
     *
     * @param value the raw value from the context map; may be {@code null}
     * @return a new mutable {@link java.util.HashMap} copy; empty when {@code value}
     *         is not a {@link Map}
     */
    public Map<String, Object> asMutableMap(final Object value) {
        return new HashMap<>(asMap(value));
    }

    /**
     * Converts a raw context value to a {@code Map<String, Object>} using
     * {@link ObjectMapper} type coercion.
     *
     * <p>Prefer this over {@link #asMap} when the raw value may contain numeric
     * keys or requires deep type conversion by Jackson (e.g., nested objects
     * deserialized from JSON that were re-serialized as {@code LinkedHashMap}).
     *
     * @param value the raw value from the context map; may be {@code null}
     * @return a typed map produced by {@link ObjectMapper#convertValue}; empty
     *         immutable map when {@code value} is not a {@link Map}
     */
    public Map<String, Object> resolveContextMapValue(final Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            return objectMapper.convertValue(mapValue, new TypeReference<>() {});
        }
        return Map.of();
    }

    /**
     * Casts a raw context value to an unmodifiable {@code List<Object>}.
     *
     * @param value the raw value from the context map; may be {@code null}
     * @return the cast list, or an empty immutable list when {@code value} is not
     *         a {@link java.util.List}
     */
    @SuppressWarnings("unchecked")
    public List<Object> asList(final Object value) {
        if (value instanceof List<?> listValue) {
            return (List<Object>) listValue;
        }
        return List.of();
    }

    /**
     * Converts a raw context value to a {@code boolean}.
     *
     * <p>Accepts {@link Boolean} instances directly and parses {@link String}
     * values via {@link Boolean#parseBoolean}.
     *
     * @param value    the raw value from the context map; may be {@code null}
     * @param fallback the value to return when {@code value} is absent or not
     *                 a recognizable boolean type
     * @return the boolean value, or {@code fallback}
     */
    public boolean asBoolean(final Object value, final boolean fallback) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof String strValue) {
            return Boolean.parseBoolean(strValue);
        }
        return fallback;
    }

    /**
     * Converts a raw context value to an {@code int}.
     *
     * <p>Accepts any {@link Number} via {@link Number#intValue()} and parses
     * {@link String} values via {@link Integer#parseInt}.
     *
     * @param value    the raw value from the context map; may be {@code null}
     * @param fallback the value to return when {@code value} is absent or cannot
     *                 be converted to an integer
     * @return the integer value, or {@code fallback}
     */
    public int asInt(final Object value, final int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String strValue) {
            try {
                return Integer.parseInt(strValue);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    // ─── Context mutation helpers ────────────────────────────────────────────

    /**
     * Increments the {@link #CONTEXT_KEY_TOTAL_TOOL_CALLS} counter in {@code context}.
     *
     * <p>Call this once per successful tool invocation to maintain a running
     * total for observability and quota enforcement.
     *
     * @param context the mutable session context map to update
     */
    public void incrementToolCalls(final Map<String, Object> context) {
        Object current = context.get(CONTEXT_KEY_TOTAL_TOOL_CALLS);
        int count = current instanceof Number number ? number.intValue() : 0;
        context.put(CONTEXT_KEY_TOTAL_TOOL_CALLS, count + 1);
    }

    // ─── Value checks ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when a context value is effectively absent.
     *
     * <p>A value is considered absent when it is {@code null}, blank, or equal
     * to {@link #PENDING_CONTEXT_VALUE}. Use this to guard slot-filling logic
     * that should prompt the user for missing information.
     *
     * @param value the string value to test
     * @return {@code true} if the value is absent or is the pending placeholder
     */
    public boolean isMissingContextValue(final String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return true;
        }
        return PENDING_CONTEXT_VALUE.equalsIgnoreCase(normalized);
    }

    // ─── Generic string utilities ────────────────────────────────────────────

    /**
     * Trims {@code value} and returns {@code null} when the result is empty.
     *
     * @param value the string to normalize; may be {@code null}
     * @return the trimmed string, or {@code null} if the trimmed result is empty
     */
    public String normalize(final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Joins all non-blank values with a single space, skipping {@code null} and
     * blank entries.
     *
     * @param values the strings to join; individual elements may be {@code null}
     * @return a space-joined string containing only the non-blank inputs, or
     *         {@code null} if all inputs are blank or absent
     */
    public String joinNonBlank(final String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(value.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ─── Self-correcting tool loop helpers ───────────────────────────────────

    /**
     * Records a tool failure in the context for use by the self-correcting loop.
     * <p>
     * Call this from {@code BaseToolExecutionAspect.onToolError} (or equivalent) to
     * make error context available to the next LLM turn.
     *
     * @param context      the session context map (mutable)
     * @param toolName     the name of the tool that failed
     * @param errorMessage the error message
     */
    public void recordToolError(
            final Map<String, Object> context, final String toolName, final String errorMessage) {
        context.put(CONTEXT_KEY_LAST_TOOL_ERROR, errorMessage);
        context.put(CONTEXT_KEY_LAST_TOOL_NAME, toolName);
        int retries = asInt(context.get(CONTEXT_KEY_TOOL_RETRY_COUNT), 0);
        context.put(CONTEXT_KEY_TOOL_RETRY_COUNT, retries + 1);
    }

    /**
     * Clears tool error state from the context.
     * <p>
     * Call this from {@code BaseToolExecutionAspect.onToolSuccess} to reset
     * the retry counter after a successful tool call.
     *
     * @param context the session context map (mutable)
     */
    public void clearToolError(final Map<String, Object> context) {
        context.remove(CONTEXT_KEY_LAST_TOOL_ERROR);
        context.remove(CONTEXT_KEY_LAST_TOOL_NAME);
        context.remove(CONTEXT_KEY_TOOL_RETRY_COUNT);
    }

    /**
     * Builds a self-correction injection string from the current context.
     * <p>
     * Append this to the user prompt in {@code buildUserPrompt} to inform
     * the LLM about the previous failure so it can try a different approach.
     *
     * <pre>{@code
     * @Override
     * protected String buildUserPrompt(MySessionDO session, String message, Map<String, Object> context) {
     *     String errorContext = sessionContextManager.buildToolErrorInjection(context);
     *     return myPromptBuilder.build(session, message) + errorContext;
     * }
     * }</pre>
     *
     * @param context the session context map
     * @return a formatted error injection string, or empty string if no error recorded
     */
    public String buildToolErrorInjection(final Map<String, Object> context) {
        String lastError = asString(context.get(CONTEXT_KEY_LAST_TOOL_ERROR), null);
        if (lastError == null) {
            return "";
        }
        String lastTool = asString(context.get(CONTEXT_KEY_LAST_TOOL_NAME), null);
        int retries = asInt(context.get(CONTEXT_KEY_TOOL_RETRY_COUNT), 0);
        String retryNote = retries > 1 ? " (attempt " + retries + ")" : "";
        String toolNote = lastTool != null ? " '" + lastTool + "'" : "";
        return "\n\n[TOOL ERROR CONTEXT" + retryNote + "]\n"
                + "The last tool call" + toolNote + " failed with: " + lastError + "\n"
                + "Please self-correct and try a different approach to satisfy the user's request.";
    }
}
