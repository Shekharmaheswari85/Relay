/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Composed annotation that activates the full agent-core framework in a Spring application.
 *
 * <p>Annotate a {@code @Configuration} class — typically your application's main class —
 * to import both core infrastructure and LLM client auto-configuration in one step:
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableRelay
 * public class MyAgentApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyAgentApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <h3>What gets imported</h3>
 * <ul>
 *   <li>{@link RelayAutoConfiguration} — registers session context propagation,
 *       cache backends (in-memory or Redis), virtual-thread task executor, and the
 *       {@link io.relay.prompt.PromptLoader}.</li>
 *   <li>{@link ChatClientAutoConfiguration} — builds the
 *       {@link io.relay.llm.ChatClientRegistry} with pre-configured reasoning and
 *       utility {@link org.springframework.ai.chat.client.ChatClient} instances, wires
 *       in any {@link LlmGatewayHeadersContributor} beans, and applies SSL/TLS settings.</li>
 * </ul>
 *
 * <h3>Spring Boot auto-configuration alternative</h3>
 * <p>Spring Boot projects that include this library on the classpath do not need this
 * annotation — both configurations are listed in {@code META-INF/spring/AutoConfiguration.imports}
 * and activate automatically.  Use {@code @EnableRelay} only when:
 * <ul>
 *   <li>you need explicit control over which configuration classes are active, or</li>
 *   <li>you are using plain Spring (not Spring Boot) without auto-configuration.</li>
 * </ul>
 *
 * @see RelayAutoConfiguration
 * @see ChatClientAutoConfiguration
 * @see RelayProperties
 * @see AgentLlmProperties
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import({RelayAutoConfiguration.class, ChatClientAutoConfiguration.class})
public @interface EnableRelay {

    /**
     * Enables audit logging via {@code BaseAuditAdvisor}.
     *
     * <p>When set to {@code false}, the audit advisor is still present in the context
     * but its {@code saveAuditTrace} callback is suppressed, preventing audit records
     * from being written.  Useful in test or developer-mode profiles.
     *
     * @return {@code true} to activate audit logging (default); {@code false} to suppress it
     */
    boolean enableAudit() default true;

    /**
     * Enables Micrometer-based observability (metrics counters, timers, and MDC context
     * management via {@link io.relay.observability.AgentObservabilityService}).
     *
     * <p>Set to {@code false} only in environments where no {@code MeterRegistry} is
     * available and you do not want the auto-wiring to fail.
     *
     * @return {@code true} to activate observability (default); {@code false} to suppress it
     */
    boolean enableObservability() default true;
}
