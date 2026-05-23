/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.memory.persona;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@link PersonaStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Suitable for development and single-node deployments. Persona data is lost on
 * application restart. For persistent persona storage, provide a JPA- or Redis-backed
 * implementation.
 */
@Slf4j
public class InMemoryPersonaStore implements PersonaStore {

    private final Map<String, PersonaMemory> store = new ConcurrentHashMap<>();

    /**
     * Returns the persona for the given user, if one has been created.
     *
     * @param userId the user identifier; never {@code null}
     * @return an {@link Optional} containing the persona, or empty if not yet created
     */
    @Override
    public Optional<PersonaMemory> getPersona(final String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return Optional.ofNullable(store.get(userId));
    }

    /**
     * Persists a persona, replacing any existing persona for the same user.
     *
     * @param persona the persona to save; never {@code null}
     */
    @Override
    public void savePersona(final PersonaMemory persona) {
        Objects.requireNonNull(persona, "persona must not be null");
        store.put(persona.userId(), persona);
        log.debug("Persona saved for user: {}", persona.userId());
    }

    /**
     * Adds a preference to the user's persona, creating an empty persona first if none exists.
     * Blank preference values are silently ignored.
     *
     * @param userId     the user identifier; never {@code null}
     * @param preference the preference to add; blank values are ignored
     */
    @Override
    public void addPreference(final String userId, final String preference) {
        Objects.requireNonNull(userId, "userId must not be null");
        PersonaMemory current = store.getOrDefault(userId, PersonaMemory.empty(userId));
        store.put(userId, current.withPreference(preference));
    }

    /**
     * Adds a goal to the user's persona, creating an empty persona first if none exists.
     * Blank goal values are silently ignored.
     *
     * @param userId the user identifier; never {@code null}
     * @param goal   the goal to add; blank values are ignored
     */
    @Override
    public void addGoal(final String userId, final String goal) {
        Objects.requireNonNull(userId, "userId must not be null");
        PersonaMemory current = store.getOrDefault(userId, PersonaMemory.empty(userId));
        store.put(userId, current.withGoal(goal));
    }

    /**
     * Sets or updates a key-value attribute on the user's persona, creating an empty
     * persona first if none exists.
     *
     * @param userId the user identifier; never {@code null}
     * @param key    the attribute key; never {@code null}
     * @param value  the attribute value
     */
    @Override
    public void setAttribute(final String userId, final String key, final String value) {
        Objects.requireNonNull(userId, "userId must not be null");
        PersonaMemory current = store.getOrDefault(userId, PersonaMemory.empty(userId));
        store.put(userId, current.withAttribute(key, value));
    }

    /**
     * Sets the communication style on the user's persona, creating an empty persona first
     * if none exists.
     *
     * @param userId the user identifier; never {@code null}
     * @param style  the communication style (e.g. {@code "technical"}, {@code "brief"})
     */
    @Override
    public void setCommunicationStyle(final String userId, final String style) {
        Objects.requireNonNull(userId, "userId must not be null");
        PersonaMemory current = store.getOrDefault(userId, PersonaMemory.empty(userId));
        store.put(userId, current.withCommunicationStyle(style));
    }

    /**
     * Removes all persona data for the given user.
     *
     * @param userId the user identifier; never {@code null}
     */
    @Override
    public void forget(final String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        store.remove(userId);
        log.debug("Persona forgotten for user: {}", userId);
    }
}
