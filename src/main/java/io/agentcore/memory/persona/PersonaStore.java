/*
 * Copyright 2024-2025 the original authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
