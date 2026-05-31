/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

/**
 * Exception thrown when a streaming operation fails.
 */
public class StreamingException extends RuntimeException {

    /**
     * Creates a new streaming exception.
     *
     * @param message the error message
     */
    public StreamingException(final String message) {
        super(message);
    }

    /**
     * Creates a new streaming exception with a cause.
     *
     * @param message the error message
     * @param cause the root cause
     */
    public StreamingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
