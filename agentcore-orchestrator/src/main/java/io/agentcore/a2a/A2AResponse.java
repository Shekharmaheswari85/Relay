/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

/**
 * Generic wrapper for HTTP responses received from remote A2A agent endpoints.
 *
 * <p>Captures the HTTP status code, the parsed response body (for successful 2xx responses),
 * and an error message (for non-2xx responses). Always check {@link #isSuccess()} before
 * calling {@link #getData()}.
 *
 * <p>Instances are created via the static factory methods {@link #ok(int, Object)} and
 * {@link #error(int, String)} — the constructor is private.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * A2AResponse<CreateSessionResponse> response = parseResponse(httpResponse, CreateSessionResponse.class);
 * if (response.isSuccess()) {
 *     String sessionId = response.getData().sessionId();
 * }
 * }</pre>
 *
 * @param <T> the type of the parsed response body
 * @see AgentClient
 */
public final class A2AResponse<T> {

    private final int statusCode;
    private final T data;
    private final String errorMessage;

    private A2AResponse(final int statusCode, final T data, final String errorMessage) {
        this.statusCode = statusCode;
        this.data = data;
        this.errorMessage = errorMessage;
    }

    /**
     * Creates a successful response wrapping the parsed body.
     *
     * @param statusCode the HTTP 2xx status code
     * @param data       the parsed response body; may be {@code null} if the response had no body
     * @param <T>        the response body type
     * @return a successful {@code A2AResponse}
     */
    public static <T> A2AResponse<T> ok(final int statusCode, final T data) {
        return new A2AResponse<>(statusCode, data, null);
    }

    /**
     * Creates an error response with no parsed body.
     *
     * @param statusCode   the HTTP 4xx or 5xx status code
     * @param errorMessage a human-readable description of the error
     * @param <T>          the response body type
     * @return an error {@code A2AResponse}
     */
    public static <T> A2AResponse<T> error(final int statusCode, final String errorMessage) {
        return new A2AResponse<>(statusCode, null, errorMessage);
    }

    /**
     * Returns {@code true} if the HTTP status code is in the 2xx (success) range.
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Returns the HTTP status code.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the parsed response body, or {@code null} for error responses.
     */
    public T getData() {
        return data;
    }

    /**
     * Returns the error description for non-2xx responses, or {@code null} for successes.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
