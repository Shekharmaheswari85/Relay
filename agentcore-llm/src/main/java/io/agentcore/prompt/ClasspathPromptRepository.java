/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.prompt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link PromptRepository} implementation that reads prompt templates from the
 * application classpath via {@link PromptLoader}.
 *
 * <p>This bean is registered automatically by Spring Boot auto-configuration and is
 * active only when no other {@link PromptRepository} bean is present in the application
 * context ({@code @ConditionalOnMissingBean}). To replace it with a database-backed or
 * remote implementation, declare any {@link PromptRepository} bean and this class will
 * be bypassed entirely.
 *
 * <h3>Prompt key format</h3>
 * <p>The prompt key is a classpath-relative path with no leading slash:
 * <pre>{@code
 * promptRepository.load("prompts/system-prompt.txt");
 * promptRepository.load("prompts/onboarding/step-1.txt");
 * }</pre>
 *
 * <h3>Caching</h3>
 * <p>Content is cached in-process by {@link PromptLoader} on first access. Subsequent
 * calls for the same key return the cached string without disk I/O.
 *
 * <h3>Versioning</h3>
 * <p>Classpath files are not versioned. {@link #load(String, String)} ignores the
 * {@code version} parameter and always returns the current classpath content.
 * Adopt a database- or object-storage-backed {@link PromptRepository} when prompt
 * versioning or hot-reload is required.
 *
 * @see PromptLoader
 * @see PromptRepository
 */
@Component
@ConditionalOnMissingBean(PromptRepository.class)
public class ClasspathPromptRepository implements PromptRepository {

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link PromptLoader#load(String)}, returning the cached classpath
     * content on all calls after the first.
     */
    @Override
    public String load(final String promptKey) {
        return PromptLoader.load(promptKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The classpath does not support versioning; the {@code version} parameter is
     * ignored and the current classpath content is returned.
     */
    @Override
    public String load(final String promptKey, final String version) {
        return PromptLoader.load(promptKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link PromptLoader#exists(String)}, which probes the context
     * class loader without loading the file content.
     */
    @Override
    public boolean exists(final String promptKey) {
        return PromptLoader.exists(promptKey);
    }
}
