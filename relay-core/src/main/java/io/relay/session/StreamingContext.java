/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Manages streaming context for a session, including buffering state and event metrics.
 *
 * <p>Tracks streaming statistics (tokens sent, bytes transmitted, buffering info)
 * and provides context-specific configurations for streaming behavior.
 */
@Getter
@RequiredArgsConstructor
public class StreamingContext {

    private final String sessionId;
    private volatile int tokenCount = 0;
    private volatile long bytesTransmitted = 0;
    private volatile int currentBufferSize = 0;
    private volatile long startTime = System.currentTimeMillis();
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * Records that a token was sent.
     *
     * @param tokenLength the character length of the token
     */
    public void recordToken(final int tokenLength) {
        this.tokenCount++;
        this.bytesTransmitted += tokenLength;
    }

    /**
     * Updates the current buffer size.
     *
     * @param size the current buffer size
     */
    public void updateBufferSize(final int size) {
        this.currentBufferSize = size;
    }

    /**
     * Sets custom metadata for this streaming session.
     *
     * @param key the metadata key
     * @param value the metadata value
     */
    public void setMetadata(final String key, final Object value) {
        this.metadata.put(Objects.requireNonNull(key), value);
    }

    /**
     * Gets elapsed time since streaming started (milliseconds).
     *
     * @return elapsed time in milliseconds
     */
    public long getElapsedTimeMs() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Calculates throughput (tokens per second).
     *
     * @return tokens per second
     */
    public double getTokensPerSecond() {
        long elapsed = getElapsedTimeMs();
        if (elapsed == 0) return 0;
        return (tokenCount * 1000.0) / elapsed;
    }
}
