/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.memory.persona;

import java.util.Optional;

/**
 * SPI for persisting and retrieving {@link PersonaMemory user persona profiles}.
 *
 * <p>The persona store is the backing repository for user-scoped, cross-session memory.
 * The default {@link InMemoryPersonaStore} is auto-configured for development; production
 * deployments should provide a JPA- or Redis-backed implementation to ensure persona
 * memory persists across application restarts.
 *
 * @see PersonaMemory
 * @see InMemoryPersonaStore
 */
public interface PersonaStore {

    /**
     * Returns the persona for the given user, if one has been created.
     *
     * @param userId the user identifier; never {@code null}
     * @return an {@link Optional} containing the persona, or empty if not yet created
     */
    Optional<PersonaMemory> getPersona(String userId);

    /**
     * Persists a persona, replacing any existing persona for the same user.
     *
     * @param persona the persona to save; never {@code null}
     */
    void savePersona(PersonaMemory persona);

    /**
     * Adds a preference to the user's persona. Creates an empty persona first if none exists.
     *
     * @param userId     the user identifier; never {@code null}
     * @param preference the preference to add; blank values are ignored
     */
    void addPreference(String userId, String preference);

    /**
     * Adds a goal to the user's persona. Creates an empty persona first if none exists.
     *
     * @param userId the user identifier; never {@code null}
     * @param goal   the goal to add; blank values are ignored
     */
    void addGoal(String userId, String goal);

    /**
     * Sets or updates a key-value attribute on the user's persona.
     *
     * @param userId the user identifier; never {@code null}
     * @param key    the attribute key; never {@code null}
     * @param value  the attribute value
     */
    void setAttribute(String userId, String key, String value);

    /**
     * Sets the communication style on the user's persona.
     *
     * @param userId the user identifier; never {@code null}
     * @param style  the communication style (e.g. {@code "technical"}, {@code "brief"})
     */
    void setCommunicationStyle(String userId, String style);

    /**
     * Removes all persona data for the given user.
     *
     * @param userId the user identifier; never {@code null}
     */
    void forget(String userId);
}
