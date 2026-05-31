/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for streaming-related errors.
 *
 * <p>Handles SSE emitter timeouts, completion, and I/O errors gracefully.
 * Ensures that streaming responses are properly closed even when errors occur.
 */
@Slf4j
@ControllerAdvice
public class StreamingControllerAdvice {

    /**
     * Handles SSE emitter timeouts.
     *
     * @param ex the timeout exception
     * @return error response
     */
    @ExceptionHandler(SseEmitter.AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleSseTimeout(final SseEmitter.AsyncRequestTimeoutException ex) {
        log.warn("SSE emitter timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .body("Streaming connection timed out");
    }

    /**
     * Handles I/O errors during streaming.
     *
     * @param ex the I/O exception
     * @return error response
     */
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<String> handleStreamingIOException(final java.io.IOException ex) {
        log.error("I/O error during streaming: {}", ex.getMessage());
        // Client may have already disconnected; response may not be sent
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Streaming communication error");
    }

    /**
     * Handles generic streaming errors.
     *
     * @param ex the exception
     * @return error response
     */
    @ExceptionHandler(StreamingException.class)
    public ResponseEntity<String> handleStreamingException(final StreamingException ex) {
        log.error("Streaming error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ex.getMessage());
    }
}
