/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.a2a;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Singleton wrapper around {@link java.net.http.HttpClient} shared by all A2A agent clients.
 *
 * <p>A single {@link HttpClient} instance is created once at startup with the configured
 * connect timeout and reused for every outbound A2A request. The JDK {@code HttpClient}
 * maintains an internal connection pool (HTTP/1.1 keep-alive and HTTP/2 multiplexing),
 * so sharing one instance gives better connection reuse than creating a new client per call.
 *
 * <p>Registered as a Spring {@code @Bean} by {@link AgentClientRegistry.AutoConfig} when
 * {@code relay.a2a.enabled=true}. Override by declaring your own {@code A2AHttpClient} bean
 * (e.g., with custom TLS settings or a proxy).
 *
 * <h3>Thread safety</h3>
 * <p>{@link HttpClient} is thread-safe by design. All three delegate methods are safe for
 * concurrent use by virtual threads.
 *
 * @see AgentClient
 * @see AgentClientRegistry
 */
public final class A2AHttpClient {

    private final HttpClient delegate;

    /**
     * Creates the singleton client with the given connect timeout.
     *
     * @param connectTimeout maximum time to wait for a TCP connection to be established;
     *                       must not be {@code null}
     */
    public A2AHttpClient(final Duration connectTimeout) {
        this.delegate = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * Sends a request and returns the full response body as a {@link String}.
     * Used for JSON endpoints (create-session, etc.).
     *
     * @param request the HTTP request to execute
     * @return the HTTP response with a String body
     * @throws IOException          if an I/O error occurs when sending or receiving
     * @throws InterruptedException if the operation is interrupted
     */
    public HttpResponse<String> send(final HttpRequest request) throws IOException, InterruptedException {
        return delegate.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a request and streams the response body as individual lines.
     * Used for SSE ({@code text/event-stream}) endpoints.
     *
     * @param request the HTTP request to execute
     * @return the HTTP response with a lazy line-stream body
     * @throws IOException          if an I/O error occurs when sending or receiving
     * @throws InterruptedException if the operation is interrupted
     */
    public HttpResponse<Stream<String>> sendForLines(final HttpRequest request)
            throws IOException, InterruptedException {
        return delegate.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    /**
     * Sends a request and discards the response body entirely.
     * Used for fire-and-forget calls such as session deletion.
     *
     * @param request the HTTP request to execute
     * @throws IOException          if an I/O error occurs when sending or receiving
     * @throws InterruptedException if the operation is interrupted
     */
    public void sendDiscarding(final HttpRequest request) throws IOException, InterruptedException {
        delegate.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
