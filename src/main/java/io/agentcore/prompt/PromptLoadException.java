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

/**
 * Unchecked exception thrown when a prompt template cannot be located or read.
 *
 * <p>Thrown by {@link PromptLoader} and {@link PromptRepository} implementations when a
 * requested prompt file is absent from the classpath, when an I/O error occurs during
 * reading, or when the backing store reports that the prompt key does not exist.
 *
 * <p>Being unchecked allows prompt-loading code to propagate the failure up to a
 * Spring Boot startup listener or controller-advice handler, enabling fail-fast
 * behaviour at application startup without burdening every call site with
 * {@code try/catch} blocks.
 *
 * <p>Callers that must distinguish "not found" from "I/O error" should inspect the
 * message text or the optional {@link #getCause()} wrapped {@link java.io.IOException}.
 *
 * @see PromptLoader
 * @see PromptRepository
 */
public class PromptLoadException extends RuntimeException {

    /**
     * Constructs a {@code PromptLoadException} with the given detail message.
     *
     * @param message a human-readable description of the failure, including the
     *                prompt key or classpath path that could not be resolved;
     *                never null
     */
    public PromptLoadException(final String message) {
        super(message);
    }

    /**
     * Constructs a {@code PromptLoadException} with the given detail message and
     * a cause that provides lower-level failure information.
     *
     * @param message a human-readable description of the failure; never null
     * @param cause   the underlying exception (typically an {@link java.io.IOException});
     *                may be null
     */
    public PromptLoadException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
