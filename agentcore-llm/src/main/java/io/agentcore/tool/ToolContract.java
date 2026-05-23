/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.agentcore.tool;

import java.util.function.Function;

/**
 * Typed function contract for a single agent tool operation.
 *
 * <p>Implementing this interface on an {@link AgentTool} class makes the tool's
 * input and output types explicit in the Java type system. This provides three
 * practical benefits:
 * <ol>
 *   <li><b>Type safety</b> — the compiler enforces that the request and response
 *       types match throughout the agent pipeline.</li>
 *   <li><b>Testability</b> — tests can instantiate or mock the {@code ToolContract}
 *       directly without spinning up a Spring context.</li>
 *   <li><b>Composability</b> — because {@code ToolContract} extends
 *       {@link java.util.function.Function}, it integrates naturally with functional
 *       pipelines and virtual-thread executors.</li>
 * </ol>
 *
 * <p>Declare the request and response as inner records to keep the tool self-contained.
 * Annotate required fields with {@code @JsonProperty(required = true)} so that Spring AI
 * generates accurate JSON-schema descriptions for the LLM.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @AgentTool(category = ToolCategory.DISCOVERY)
 * public class LookupTicketTool implements ToolContract<LookupTicketTool.Request,
 *                                                       LookupTicketTool.Response> {
 *
 *     @Override
 *     @Tool(name = "lookupTicket", description = "Returns the ticket matching the given ID")
 *     public Response apply(Request request) {
 *         Ticket ticket = ticketService.findById(request.ticketId());
 *         return ticket != null
 *             ? new Response(true, ticket, null)
 *             : new Response(false, null, "Ticket not found");
 *     }
 *
 *     public record Request(@JsonProperty(required = true) String ticketId) {}
 *     public record Response(boolean found, Ticket ticket, String error) {}
 * }
 * }</pre>
 *
 * @param <REQ> the tool's input type; typically an inner {@code record} whose fields
 *              map to the JSON parameters the LLM sends
 * @param <RES> the tool's output type; typically an inner {@code record} serialized
 *              back to the LLM as a JSON string
 */
@FunctionalInterface
public interface ToolContract<REQ, RES> extends Function<REQ, RES> {

    /**
     * Executes the tool with the given request and returns its result.
     *
     * <p>This method is invoked by the Spring AI function-calling infrastructure after
     * deserializing the LLM's tool-call arguments into {@code REQ}. The returned value
     * is serialized to JSON and sent back to the LLM as the tool result.
     *
     * @param request the deserialized tool input; never {@code null}
     * @return the tool output to return to the LLM; must not be {@code null} — return a
     *         structured error object instead of throwing where possible
     */
    @Override
    RES apply(REQ request);
}
