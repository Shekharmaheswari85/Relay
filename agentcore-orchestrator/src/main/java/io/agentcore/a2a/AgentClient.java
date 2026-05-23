/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.a2a;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

/**
 * HTTP client for calling a remote agent that exposes the standard agentcore REST API.
 *
 * <p>{@code AgentClient} handles the complete A2A (Agent-to-Agent) call flow:
 * <ol>
 *   <li>Creates a session on the remote agent ({@code POST {basePath}/sessions})</li>
 *   <li>Sends a message and receives a streaming SSE response, forwarding each
 *       {@link SseEvent} to a caller-supplied consumer
 *       ({@code POST {basePath}/sessions/{id}/messages})</li>
 *   <li>Optionally deletes the session when the interaction is complete
 *       ({@code DELETE {basePath}/sessions/{id}})</li>
 * </ol>
 *
 * <p>All HTTP calls are delegated to a shared singleton {@link A2AHttpClient} — no new
 * JDK {@code HttpClient} is created per agent or per request. SSE stream parsing is handled
 * by {@link SseEventParser}. HTTP responses are wrapped in {@link A2AResponse} before the
 * body is extracted, giving callers a consistent status + data + error structure.
 *
 * <h3>Usage via {@link AgentClientRegistry}</h3>
 * <pre>{@code
 * @Component
 * public class InventorySubAgent extends RemoteAgentSubAgent<MySession, MyStep> {
 *
 *     public InventorySubAgent(AgentClientRegistry registry) {
 *         super(registry.get("inventory-agent"));
 *     }
 *
 *     @Override
 *     public boolean canHandle(MySession session, MyStep step) {
 *         return step == MyStep.INVENTORY_CHECK;
 *     }
 * }
 * }</pre>
 *
 * <h3>Auth</h3>
 * <p>Auth headers are contributed by {@link A2AAuthContributor} beans registered in the
 * application context. The client calls each contributor before every outbound request.
 *
 * <h3>Thread safety</h3>
 * <p>Instances are stateless and safe for concurrent use by multiple virtual threads.
 *
 * @see AgentClientRegistry
 * @see A2AHttpClient
 * @see SseEventParser
 * @see A2AResponse
 * @see RemoteAgentSubAgent
 * @see A2AAuthContributor
 * @see SseEvent
 */
@Slf4j
public class AgentClient {

    private static final String DEFAULT_BASE_PATH = "/api/agent";
    private static final Duration SESSION_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * -- GETTER --
     *  Returns the logical name of this remote agent.
     */
    @Getter
    private final String name;
    private final String baseUrl;
    private final String basePath;
    private final Duration responseTimeout;
    private final List<A2AAuthContributor> authContributors;
    private final A2AHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructs an {@code AgentClient}.
     *
     * @param name             logical name of this remote agent (used in logs and auth contributors)
     * @param baseUrl          base URL of the remote agent (no trailing slash)
     * @param basePath         API path prefix (e.g., {@code "/api/agent"})
     * @param responseTimeout  maximum time to wait for the SSE stream to complete
     * @param authContributors list of contributors that inject auth headers; may be empty
     * @param httpClient       the shared singleton HTTP client; never {@code null}
     * @param objectMapper     the Jackson mapper for serialising requests and deserialising
     *                         responses; never {@code null}
     */
    public AgentClient(
            final String name,
            final String baseUrl,
            final String basePath,
            final Duration responseTimeout,
            final List<A2AAuthContributor> authContributors,
            final A2AHttpClient httpClient,
            final ObjectMapper objectMapper) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.basePath = basePath != null ? basePath : DEFAULT_BASE_PATH;
        this.responseTimeout = responseTimeout != null ? responseTimeout : Duration.ofSeconds(120);
        this.authContributors = authContributors != null ? authContributors : List.of();
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Creates a new session on the remote agent and returns the assigned session ID.
     *
     * <p>Calls {@code POST {basePath}/sessions} with a {@link CreateSessionRequest} body.
     * The response is parsed into a {@link CreateSessionResponse} via {@link A2AResponse}.
     * Blocks the calling virtual thread until the HTTP response is received.
     *
     * @param agentId   the agent ID to pass in the create-session request body; may be {@code null}
     * @param createdBy the user or service identifier creating the session; may be {@code null}
     * @return the remote session ID; never {@code null}
     * @throws IllegalStateException if the remote agent does not return a {@code sessionId}
     * @throws AgentClientException  if the HTTP call fails
     */
    public String createSession(final String agentId, final String createdBy) {
        CreateSessionRequest requestBody = new CreateSessionRequest(
                agentId != null ? agentId : "",
                createdBy != null ? createdBy : "a2a-client");
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = buildRequest()
                    .uri(URI.create(baseUrl + basePath + "/sessions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(SESSION_REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request);
            A2AResponse<CreateSessionResponse> response =
                    parseResponse(httpResponse, CreateSessionResponse.class);

            if (!response.isSuccess() || response.getData() == null
                    || response.getData().sessionId() == null) {
                String detail = response.isSuccess()
                        ? "empty or missing sessionId in response body"
                        : response.getErrorMessage();
                throw new IllegalStateException(
                        "Remote agent '" + name + "' did not return a sessionId: " + detail);
            }

            log.debug("A2A session created on '{}': sessionId={}", name, response.getData().sessionId());
            return response.getData().sessionId();

        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentClientException("Failed to create session on agent '" + name + "'", ex);
        }
    }

    /**
     * Sends a message to an existing remote session and forwards each received
     * {@link SseEvent} to the given consumer.
     *
     * <p>Calls {@code POST {basePath}/sessions/{sessionId}/messages} with a
     * {@link SendMessageRequest} body. Both {@code text/event-stream} and
     * {@code application/json} response formats are supported — the parser is chosen
     * based on the actual {@code Content-Type} returned by the remote agent:
     * <ul>
     *   <li><strong>{@code text/event-stream}</strong> — the response is piped through
     *       {@link SseEventParser}; {@code onEvent} is called once per complete SSE block.</li>
     *   <li><strong>{@code application/json}</strong> — the full JSON body is read into a
     *       single {@link SseEvent} (type {@code "message"}) and {@code onEvent} is called
     *       once.</li>
     * </ul>
     *
     * <p>The method blocks the calling virtual thread until the stream ends or the
     * configured {@code responseTimeout} elapses.
     *
     * @param sessionId the remote session ID (returned by {@link #createSession})
     * @param message   the user message to send; may be {@code null} (treated as empty)
     * @param onEvent   consumer invoked for each {@link SseEvent} received; never {@code null}
     * @throws IllegalStateException if the remote agent returns a non-2xx status
     * @throws AgentClientException  if the HTTP call fails
     */
    public void sendMessage(
            final String sessionId,
            final String message,
            final Consumer<SseEvent> onEvent) {
        Objects.requireNonNull(onEvent, "onEvent consumer must not be null");
        SendMessageRequest requestBody = new SendMessageRequest(message != null ? message : "");
        try {
            String json = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = buildRequest()
                    .uri(URI.create(baseUrl + basePath + "/sessions/" + sessionId + "/messages"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(responseTimeout)
                    .build();

            log.debug("A2A message sent to '{}': sessionId={}", name, sessionId);
            HttpResponse<Stream<String>> response = httpClient.sendForLines(request);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = response.body().collect(Collectors.joining());
                throw new IllegalStateException(
                        "Remote agent '" + name + "' returned HTTP "
                        + response.statusCode() + ": " + errorBody);
            }

            String contentType = response.headers()
                    .firstValue("content-type")
                    .orElse("text/event-stream");

            if (contentType.contains("application/json")) {
                String body = response.body().collect(Collectors.joining());
                onEvent.accept(new SseEvent("message", body));
            } else {
                SseEventParser.parse(response.body(), onEvent);
            }

        } catch (IllegalStateException | AgentClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentClientException(
                    "A2A stream error from '" + name + "': " + ex.getMessage(), ex);
        }
    }

    /**
     * Deletes a remote session. Errors are logged at {@code WARN} and swallowed — the
     * caller's session lifecycle is not affected by remote cleanup failures.
     *
     * @param sessionId the remote session ID to delete
     */
    public void deleteSession(final String sessionId) {
        try {
            HttpRequest request = buildRequest()
                    .uri(URI.create(baseUrl + basePath + "/sessions/" + sessionId))
                    .DELETE()
                    .timeout(DELETE_TIMEOUT)
                    .build();
            httpClient.sendDiscarding(request);
            log.debug("A2A session deleted on '{}': sessionId={}", name, sessionId);
        } catch (Exception ex) {
            log.warn("A2A session delete failed on '{}': sessionId={} error={}",
                    name, sessionId, ex.getMessage());
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private HttpRequest.Builder buildRequest() {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        HttpHeaders headers = new HttpHeaders();
        for (A2AAuthContributor contributor : authContributors) {
            try {
                contributor.contribute(headers, name);
            } catch (Exception ex) {
                log.warn("A2AAuthContributor failed for agent '{}': {}", name, ex.getMessage());
            }
        }
        headers.forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
        return builder;
    }

    private <T> A2AResponse<T> parseResponse(
            final HttpResponse<String> response, final Class<T> type) throws IOException {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return A2AResponse.ok(status, objectMapper.readValue(response.body(), type));
        }
        return A2AResponse.error(status, "HTTP " + status + ": " + response.body());
    }

    // ─── Exception ───────────────────────────────────────────────────────────

    /**
     * Signals that an A2A HTTP call failed due to a network error, timeout, or unexpected
     * response structure.
     */
    public static final class AgentClientException extends RuntimeException {

        /**
         * Creates an exception with the given message and root cause.
         *
         * @param message a description of the failure
         * @param cause   the underlying exception
         */
        public AgentClientException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
