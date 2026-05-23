/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.memory.persona;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Persistent record of a user's preferences, goals, and communication style.
 *
 * <p>{@code PersonaMemory} is the agent's long-term profile for a specific user — analogous
 * to what a human assistant would remember about a client across multiple meetings. Unlike
 * session memory (which is discarded at session end), persona memory persists indefinitely
 * and is injected into every context window for that user.
 *
 * <h3>What persona memory stores</h3>
 * <ul>
 *   <li><strong>Preferences</strong> — stylistic or operational preferences expressed by the
 *       user (e.g. {@code "prefers concise bullet-point responses"}, {@code "uses military time"}).
 *   </li>
 *   <li><strong>Goals</strong> — the user's stated objectives or persistent business goals
 *       (e.g. {@code "reduce inventory shrinkage"}, {@code "optimise reorder cycles for Q4"}).
 *   </li>
 *   <li><strong>Communication style</strong> — the agent's inferred or user-declared tone:
 *       {@code "technical"}, {@code "conversational"}, {@code "brief"}, or {@code "detailed"}.
 *   </li>
 *   <li><strong>Attributes</strong> — arbitrary key-value facts about the user that don't fit
 *       the above categories (e.g. {@code role=Category Manager}, {@code region=Southwest}).
 *   </li>
 * </ul>
 *
 * @param userId             the unique identifier of the user; never {@code null}
 * @param preferences        the list of observed/stated preferences; never {@code null}
 * @param goals              the list of stated goals; never {@code null}
 * @param communicationStyle the inferred or declared style: {@code "technical"},
 *                           {@code "conversational"}, {@code "brief"}, or {@code "detailed"};
 *                           may be {@code null} when not yet known
 * @param attributes         arbitrary key-value facts about the user; never {@code null}
 * @param lastUpdated        when this persona was last modified; never {@code null}
 */
public record PersonaMemory(
        String userId,
        List<String> preferences,
        List<String> goals,
        String communicationStyle,
        Map<String, String> attributes,
        Instant lastUpdated) {

    /** Canonical constructor — validates required fields and defensive copies collections. */
    public PersonaMemory {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
        preferences = preferences != null ? List.copyOf(preferences) : List.of();
        goals = goals != null ? List.copyOf(goals) : List.of();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    /**
     * Creates an empty persona for the given user with no preferences, goals, or style.
     *
     * @param userId the user identifier; never {@code null}
     * @return a new empty persona
     */
    public static PersonaMemory empty(final String userId) {
        return new PersonaMemory(userId, List.of(), List.of(), null, Map.of(), Instant.now());
    }

    /**
     * Returns a copy of this persona with the given preference added.
     *
     * @param preference the preference to add (e.g. {@code "prefers concise responses"})
     * @return a new persona containing the added preference
     */
    public PersonaMemory withPreference(final String preference) {
        if (preference == null || preference.isBlank()) return this;
        List<String> updated = new ArrayList<>(preferences);
        if (!updated.contains(preference)) updated.add(preference);
        return new PersonaMemory(userId, updated, goals, communicationStyle, attributes, Instant.now());
    }

    /**
     * Returns a copy of this persona with the given goal added.
     *
     * @param goal the goal to add (e.g. {@code "reduce inventory shrinkage"})
     * @return a new persona containing the added goal
     */
    public PersonaMemory withGoal(final String goal) {
        if (goal == null || goal.isBlank()) return this;
        List<String> updated = new ArrayList<>(goals);
        if (!updated.contains(goal)) updated.add(goal);
        return new PersonaMemory(userId, preferences, updated, communicationStyle, attributes, Instant.now());
    }

    /**
     * Returns a copy of this persona with the given attribute set.
     *
     * @param key   the attribute key (e.g. {@code "role"})
     * @param value the attribute value (e.g. {@code "Category Manager"})
     * @return a new persona with the attribute added or updated
     */
    public PersonaMemory withAttribute(final String key, final String value) {
        if (key == null || key.isBlank()) return this;
        var updated = new HashMap<>(attributes);
        updated.put(key, value);
        return new PersonaMemory(userId, preferences, goals, communicationStyle, Map.copyOf(updated), Instant.now());
    }

    /**
     * Returns a copy of this persona with the given communication style.
     *
     * @param style the communication style; e.g. {@code "technical"} or {@code "brief"}
     * @return a new persona with the style updated
     */
    public PersonaMemory withCommunicationStyle(final String style) {
        return new PersonaMemory(userId, preferences, goals, style, attributes, Instant.now());
    }

    /**
     * Returns {@code true} if this persona contains no meaningful information.
     */
    public boolean isEmpty() {
        return preferences.isEmpty() && goals.isEmpty()
                && (communicationStyle == null || communicationStyle.isBlank())
                && attributes.isEmpty();
    }

    /**
     * Formats this persona as a text block for injection into the LLM context window.
     *
     * <p>Returns an empty string when the persona is empty, so callers can safely skip
     * injection without checking for {@code null}.
     *
     * @return a formatted persona context string, or empty string if the persona is empty
     */
    public String toPromptFragment() {
        if (isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n[USER PERSONA]\n");
        if (!preferences.isEmpty()) {
            sb.append("Preferences: ").append(String.join("; ", preferences)).append("\n");
        }
        if (!goals.isEmpty()) {
            sb.append("Goals: ").append(String.join("; ", goals)).append("\n");
        }
        if (communicationStyle != null && !communicationStyle.isBlank()) {
            sb.append("Communication style: ").append(communicationStyle).append("\n");
        }
        if (!attributes.isEmpty()) {
            String attrs = attributes.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
            sb.append("Profile: ").append(attrs).append("\n");
        }
        sb.append("[END USER PERSONA]\n");
        return sb.toString();
    }
}
