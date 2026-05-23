/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.prompt;

import java.util.List;
import java.util.Optional;

/**
 * Service-provider interface for loading and versioning LLM prompt templates.
 *
 * <p>Defines the contract that the framework uses when it needs to read a prompt. By
 * programming against this interface rather than directly against the classpath,
 * teams can swap the backing store — database, object storage, remote configuration
 * service — without touching any framework code.
 *
 * <p>Implementors must provide {@link #load(String)}. The remaining methods carry
 * default implementations that either delegate to {@code load(String)} or return
 * sensible no-versioning fallbacks, so minimal implementations only override one method.
 *
 * <h3>Framework contract</h3>
 * <ul>
 *   <li>{@link #load(String)} never returns {@code null}; it throws {@link PromptLoadException}
 *       when the prompt cannot be found or read.</li>
 *   <li>{@link #exists(String)} and {@link #tryLoad(String)} must not throw; they translate
 *       missing-prompt conditions into {@code false} / empty-Optional respectively.</li>
 *   <li>Implementations are expected to be Spring beans so that the auto-configuration
 *       can discover and inject them.</li>
 * </ul>
 *
 * <h3>Built-in implementations</h3>
 * <ul>
 *   <li>{@link ClasspathPromptRepository} — loads from classpath files, registered automatically
 *       when no other {@code PromptRepository} bean is present</li>
 * </ul>
 *
 * <h3>Custom implementation example</h3>
 * <pre>{@code
 * @Service
 * public class DatabasePromptRepository implements PromptRepository {
 *
 *     private final PromptTemplateJpaRepository jpaRepo;
 *
 *     @Override
 *     public String load(String promptKey) {
 *         return jpaRepo.findLatestByKey(promptKey)
 *                 .map(PromptTemplate::getContent)
 *                 .orElseThrow(() -> new PromptLoadException("Prompt not found: " + promptKey));
 *     }
 *
 *     @Override
 *     public String load(String promptKey, String version) {
 *         return jpaRepo.findByKeyAndVersion(promptKey, version)
 *                 .map(PromptTemplate::getContent)
 *                 .orElseThrow(() -> new PromptLoadException(
 *                         "Prompt not found: " + promptKey + "@" + version));
 *     }
 *
 *     @Override
 *     public List<String> listVersions(String promptKey) {
 *         return jpaRepo.findVersionsByKey(promptKey);
 *     }
 * }
 * }</pre>
 *
 * @see ClasspathPromptRepository
 * @see PromptLoader
 * @see PromptLoadException
 */
public interface PromptRepository {

    /**
     * Loads the latest (or only) version of a prompt by its key.
     *
     * <p>For classpath-based repositories the key is the classpath-relative path,
     * e.g. {@code "prompts/system-prompt.txt"}. For database-backed repositories it
     * is typically a logical name registered in the prompt store.
     *
     * @param promptKey the prompt identifier; never null or blank
     * @return the full text content of the prompt; never null
     * @throws PromptLoadException if the prompt cannot be found or read
     */
    String load(String promptKey);

    /**
     * Loads a specific version of a prompt.
     *
     * <p>Repositories that do not support versioning (e.g., classpath) ignore the
     * {@code version} parameter and delegate to {@link #load(String)}.
     *
     * @param promptKey the prompt identifier; never null or blank
     * @param version   a version identifier understood by the backing store;
     *                  {@code null} is interpreted as "latest"
     * @return the full text content of the prompt; never null
     * @throws PromptLoadException if the prompt cannot be found or read
     */
    default String load(final String promptKey, final String version) {
        return load(promptKey);
    }

    /**
     * Returns all available version identifiers for a prompt, newest first.
     *
     * <p>Repositories that do not support versioning return a single-element list
     * (typically {@code ["latest"]}) rather than an empty list, so callers can always
     * expect at least one entry when the prompt key exists.
     *
     * @param promptKey the prompt identifier; never null or blank
     * @return an ordered list of version identifiers; never null; empty only if the
     *         prompt key does not exist in this repository
     */
    default List<String> listVersions(final String promptKey) {
        return List.of("latest");
    }

    /**
     * Returns {@code true} if the given prompt key can be successfully loaded from
     * this repository, {@code false} otherwise.
     *
     * <p>The default implementation attempts {@link #load(String)} and catches
     * {@link PromptLoadException}. Override for more efficient existence checks that
     * avoid loading the full prompt content.
     *
     * @param promptKey the prompt identifier; never null
     * @return {@code true} if the prompt is present and readable; {@code false} if not found
     */
    default boolean exists(final String promptKey) {
        try {
            load(promptKey);
            return true;
        } catch (PromptLoadException ex) {
            return false;
        }
    }

    /**
     * Attempts to load a prompt, returning an empty {@link java.util.Optional} instead of
     * throwing when the prompt is not found.
     *
     * <p>Use this method when the absence of a prompt is a valid condition (e.g. an
     * optional hint file) so that callers avoid exception-based control flow.
     *
     * @param promptKey the prompt identifier; never null
     * @return an Optional containing the prompt content, or empty if not found or unreadable
     */
    default Optional<String> tryLoad(final String promptKey) {
        try {
            return Optional.of(load(promptKey));
        } catch (PromptLoadException ex) {
            return Optional.empty();
        }
    }
}
