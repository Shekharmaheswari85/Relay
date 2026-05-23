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
package io.agentcore.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static utility that loads LLM prompt templates from the application classpath.
 *
 * <p>Loaded content is stored in a process-wide {@link java.util.concurrent.ConcurrentHashMap}
 * cache, so repeated calls with the same path pay no I/O cost after the first load.
 * Callers that need fresh disk content — for example during hot-reload development or
 * in tests that write temporary prompt files — use the {@code loadFresh} family of methods.
 *
 * <p>By convention, prompt files live under {@code src/main/resources/prompts/} and are
 * referenced with a path relative to the classpath root (no leading slash).
 *
 * <p>Variable substitution replaces literal placeholder tokens in the loaded template.
 * Any string may serve as a placeholder key; the convention used throughout the framework
 * is double-brace notation such as {@code {{USER_NAME}}}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Load and cache a prompt template
 * String systemPrompt = PromptLoader.load("prompts/system-prompt.txt");
 *
 * // Load with variable substitution
 * String prompt = PromptLoader.load("prompts/greeting.txt", Map.of(
 *     "{{USER_NAME}}", userName,
 *     "{{AGENT_NAME}}", "SmartServe"
 * ));
 *
 * // Bypass the cache (development / testing)
 * String fresh = PromptLoader.loadFresh("prompts/system-prompt.txt");
 *
 * // Probe without loading
 * if (PromptLoader.exists("prompts/optional-hint.txt")) { ... }
 * }</pre>
 *
 * <p>All load methods throw {@link PromptLoadException} (unchecked) when the requested file
 * is absent from the classpath or cannot be read, enabling fail-fast behaviour at application
 * startup.
 *
 * @see PromptRepository
 * @see PromptLoadException
 */
public final class PromptLoader {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptLoader() {}

    /**
     * Loads a prompt from the classpath, returning a cached copy on subsequent calls.
     *
     * @param classpathPath classpath-relative path, e.g. {@code "prompts/system-prompt.txt"};
     *                      never null
     * @return the full UTF-8 content of the prompt file; never null
     * @throws PromptLoadException if the file does not exist on the classpath or an I/O
     *                             error occurs during reading
     */
    public static String load(final String classpathPath) {
        return CACHE.computeIfAbsent(classpathPath, PromptLoader::loadFromClasspath);
    }

    /**
     * Loads a prompt from the classpath (cached) and substitutes the given variables into
     * the returned template.
     *
     * <p>Substitution is a simple {@link String#replace} over every map entry; keys that
     * appear multiple times in the template are all replaced. A {@code null} map value is
     * treated as an empty string.
     *
     * @param classpathPath classpath-relative path; never null
     * @param variables     map of placeholder token to replacement value; may be null or empty
     * @return the prompt template with all placeholder tokens replaced; never null
     * @throws PromptLoadException if the file does not exist on the classpath or an I/O
     *                             error occurs during reading
     */
    public static String load(final String classpathPath, final Map<String, String> variables) {
        String template = load(classpathPath);
        return substitute(template, variables);
    }

    /**
     * Loads a prompt from the classpath, bypassing the in-process cache.
     *
     * <p>Each call reads the file from disk. Use this method during development when prompt
     * files are actively edited, or in tests that write temporary classpath resources.
     *
     * @param classpathPath classpath-relative path; never null
     * @return the full UTF-8 content of the prompt file; never null
     * @throws PromptLoadException if the file does not exist on the classpath or an I/O
     *                             error occurs during reading
     */
    public static String loadFresh(final String classpathPath) {
        return loadFromClasspath(classpathPath);
    }

    /**
     * Loads a prompt from the classpath (bypassing the cache) and substitutes the given
     * variables into the returned template.
     *
     * @param classpathPath classpath-relative path; never null
     * @param variables     map of placeholder token to replacement value; may be null or empty
     * @return the prompt template with all placeholder tokens replaced; never null
     * @throws PromptLoadException if the file does not exist on the classpath or an I/O
     *                             error occurs during reading
     */
    public static String loadFresh(final String classpathPath, final Map<String, String> variables) {
        String template = loadFresh(classpathPath);
        return substitute(template, variables);
    }

    /**
     * Evicts all entries from the in-process prompt cache.
     *
     * <p>The next call to any {@code load} method will re-read the file from the classpath.
     * Primarily intended for test teardown and hot-reload scenarios.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Returns {@code true} if a resource at the given classpath path can be located by the
     * context class loader, without loading its content.
     *
     * @param classpathPath classpath-relative path; never null
     * @return {@code true} if the resource exists and is accessible
     */
    public static boolean exists(final String classpathPath) {
        return PromptLoader.class.getClassLoader().getResource(classpathPath) != null;
    }

    private static String loadFromClasspath(final String path) {
        try (InputStream is = PromptLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new PromptLoadException(
                        "Prompt file not found on classpath: " + path
                                + ". Ensure the file exists in src/main/resources/");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PromptLoadException("Failed to read prompt file: " + path, e);
        }
    }

    private static String substitute(final String template, final Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
