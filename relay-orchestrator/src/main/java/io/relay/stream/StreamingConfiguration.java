/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Configuration properties for streaming behavior.
 *
 * <p>Customize streaming buffer sizes, timeouts, and batching settings
 * through application properties. Example in application.yml:
 *
 * <pre>
 * relay:
 *   streaming:
 *     thinking-buffer-size: 512
 *     message-buffer-size: 256
 *     event-batch-size: 20
 *     flush-interval-ms: 50
 *     emitter-timeout-ms: 300000
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "relay.streaming")
public class StreamingConfiguration {

    /** Size of thinking phase buffer in characters. */
    private int thinkingBufferSize = 512;

    /** Size of message buffer in characters. */
    private int messageBufferSize = 256;

    /** Maximum events per batch before flushing. */
    private int eventBatchSize = 20;

    /** Milliseconds between automatic batch flushes. */
    private long flushIntervalMs = 50;

    /** SSE emitter timeout in milliseconds. */
    private long emitterTimeoutMs = 300000; // 5 minutes

    /** Whether to enable streaming (can be disabled for testing). */
    private boolean enabled = true;
}
