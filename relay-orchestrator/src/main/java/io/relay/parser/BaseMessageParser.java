/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.parser;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.relay.session.SessionContextManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Base class for parsing and classifying free-form text produced by users or LLMs.
 *
 * <p>Provides reusable utilities that most agent domains need when handling unstructured
 * LLM responses or user messages:
 * <ul>
 *   <li><strong>Approval detection</strong> — recognises short confirmation phrases such
 *       as "yes", "ok", and "proceed" that signal the user wants to continue a pending
 *       operation without providing new information.</li>
 *   <li><strong>Confirmation-required detection</strong> — identifies assistant messages
 *       that are asking the user for explicit approval before executing a mutation.</li>
 *   <li><strong>Prompt extraction</strong> — walks a nested {@code Map} structure of
 *       unknown shape (e.g. a JSON request body with arbitrary fields) to locate the
 *       user's actual intent text by searching for well-known key names.</li>
 *   <li><strong>Token sanitization</strong> — normalises raw strings and filters out
 *       noise words so that extracted identifiers are meaningful.</li>
 * </ul>
 *
 * <h3>Extending</h3>
 * <pre>{@code
 * @Component
 * public class OrderDomainParser extends BaseMessageParser {
 *
 *     public OrderDomainParser(SessionContextManager ctx) {
 *         super(ctx, Set.of("yes", "ok", "proceed", "ship it"));
 *     }
 *
 *     public Optional<String> extractOrderId(String text) {
 *         // domain-specific extraction using regex or keyword scan
 *     }
 *
 *     @Override
 *     protected boolean containsDomainSpecificContent(String message) {
 *         return ORDER_DETAIL_PATTERN.matcher(message).find();
 *     }
 * }
 * }</pre>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseMessageParser {

    /**
     * Default set of lowercase phrases that constitute a simple user confirmation with
     * no additional intent. Trailing punctuation is stripped before matching.
     */
    public static final Set<String> DEFAULT_APPROVAL_MESSAGES = Set.of(
            "yes",
            "yep",
            "yeah",
            "ok",
            "okay",
            "approve",
            "approved",
            "proceed",
            "continue",
            "go ahead",
            "confirm",
            "confirmed",
            "all",
            "retry");

    /**
     * Case-insensitive pattern that matches assistant messages asking the user for
     * explicit approval before proceeding with a pending operation.
     */
    protected static final Pattern CONFIRMATION_REQUIRED_PATTERN = Pattern.compile(
            "(?i)\\b(approve|approval|confirm|confirmation|shall i proceed|do you want me to proceed|proceed\\?)\\b");

    /**
     * Well-known map key names that are likely to hold a user's prompt or intent text.
     * Used by {@link #extractPromptFromUnknownFields(Map)} when scanning arbitrary
     * request structures for the user's actual query.
     */
    protected static final Set<String> PROMPT_KEYS =
            Set.of("content", "message", "query", "prompt", "text", "input", "userPrompt", "chatMessage", "chatInput");

    protected final SessionContextManager sessionContextManager;
    protected final Set<String> approvalMessages;

    /**
     * Constructs a parser that uses the {@link #DEFAULT_APPROVAL_MESSAGES} vocabulary.
     *
     * @param sessionContextManager the context manager used for value normalisation
     */
    protected BaseMessageParser(final SessionContextManager sessionContextManager) {
        this(sessionContextManager, DEFAULT_APPROVAL_MESSAGES);
    }

    // ─── Approval detection ─────────────────────────────────────────────────

    /**
     * Returns {@code true} when the message is a standalone user confirmation — one that
     * says "yes / ok / proceed" without supplying any new context that would change what
     * the agent does next.
     *
     * <p>The check normalises the message to lowercase, strips trailing punctuation, and
     * looks it up in the configured approval vocabulary. If a match is found,
     * {@link #containsDomainSpecificContent(String)} is consulted to exclude messages that
     * happen to start with a confirmation word but also contain intent-bearing content.
     *
     * @param messageContent the raw user message to evaluate; may be {@code null}
     * @return {@code true} if the message is a pure approval with no new information;
     *         {@code false} if the message is {@code null}, blank, not in the approval
     *         vocabulary, or contains domain-specific content
     */
    public boolean isApprovalOnlyFollowUp(final String messageContent) {
        if (messageContent == null || messageContent.isBlank()) {
            return false;
        }
        String trimmed = messageContent.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT)
                .replaceAll("[.!?]", "")
                .trim();
        if (!approvalMessages.contains(normalized)) {
            return false;
        }
        // Subclasses can override to add domain-specific checks
        return !containsDomainSpecificContent(trimmed);
    }

    /**
     * Returns {@code true} when the message contains domain-specific intent that
     * disqualifies it from being treated as a pure approval.
     *
     * <p>The default implementation always returns {@code false}. Override to detect
     * domain-specific patterns — for example, an order domain might return {@code true}
     * if the message contains an order number, ensuring "yes, order 12345" is not
     * classified as a simple approval.
     *
     * @param message the trimmed, non-blank user message to inspect
     * @return {@code true} if the message carries domain-specific content that augments
     *         or overrides a simple approval signal
     */
    protected boolean containsDomainSpecificContent(final String message) {
        return false;
    }

    /**
     * Returns {@code true} when the message contains a phrase that asks the user to
     * confirm before the agent proceeds with an operation.
     *
     * <p>Matches patterns such as "approve", "confirm", "shall I proceed?", and similar
     * phrasing. Typically applied to LLM-generated assistant messages to detect when the
     * agent is waiting for explicit user approval.
     *
     * @param messageContent the assistant or user message to evaluate; may be {@code null}
     * @return {@code true} if a confirmation-soliciting phrase is found; {@code false} if
     *         the message is {@code null}, blank, or contains no such pattern
     */
    public boolean containsConfirmationRequired(final String messageContent) {
        if (messageContent == null || messageContent.isBlank()) {
            return false;
        }
        return CONFIRMATION_REQUIRED_PATTERN.matcher(messageContent).find();
    }

    // ─── Token sanitization ─────────────────────────────────────────────────

    /**
     * Normalises a raw string extracted from a user message and returns it only if it
     * represents a meaningful identifier token.
     *
     * <p>The value is first normalised through {@link SessionContextManager#normalize},
     * then tested against the configured invalid-token set. Values that are normalised
     * to {@code null} or whose lowercase form appears in {@link #getInvalidTokens()} are
     * discarded.
     *
     * @param value the raw string to sanitise; may be {@code null}
     * @return the normalised token if valid, or {@code null} if the value is
     *         {@code null}, blank after normalisation, or a known noise word
     */
    public String sanitizeToken(final String value) {
        String normalized = sessionContextManager.normalize(value);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (getInvalidTokens().contains(lower)) {
            return null;
        }
        return normalized;
    }

    /**
     * Returns the set of lowercase strings that {@link #sanitizeToken(String)} must treat
     * as noise and discard.
     *
     * <p>The default set includes common English stopwords and generic placeholder words
     * that carry no domain meaning (e.g., "the", "filter", "value"). Override to add or
     * replace domain-specific noise words.
     *
     * @return the set of invalid token strings in lowercase; never {@code null}
     */
    protected Set<String> getInvalidTokens() {
        return Set.of(
                "the", "for", "in", "on", "to", "a", "an",
                "new", "filter", "filters", "logic", "value", "values",
                "only", "plan", "step", "status");
    }

    // ─── Prompt extraction ──────────────────────────────────────────────────

    /**
     * Searches a map of unknown structure for a user-prompt string by recursively
     * walking its keys and values.
     *
     * <p>Keys listed in {@link #PROMPT_KEYS} are checked first. If none match, the
     * method descends into nested maps and list elements. Cycles are handled by tracking
     * visited nodes. The first normalised non-blank string found is returned.
     *
     * @param additionalFields the map to search; may be {@code null} or empty
     * @return the first prompt-like string value found after normalisation, or
     *         {@code null} if no suitable value is located
     */
    public String extractPromptFromUnknownFields(final Map<String, Object> additionalFields) {
        if (additionalFields == null || additionalFields.isEmpty()) {
            return null;
        }
        return findPromptValue(additionalFields, new HashSet<>());
    }

    private String findPromptValue(final Object node, final Set<Object> visited) {
        if (node == null || visited.contains(node)) {
            return null;
        }
        visited.add(node);

        if (node instanceof String text) {
            return sessionContextManager.normalize(text);
        }

        if (node instanceof Map<?, ?> mapNode) {
            // First check known prompt keys
            for (Map.Entry<?, ?> entry : mapNode.entrySet()) {
                Object key = entry.getKey();
                if (key instanceof String keyStr && PROMPT_KEYS.contains(keyStr)) {
                    String direct = findPromptValue(entry.getValue(), visited);
                    if (direct != null) {
                        return direct;
                    }
                }
            }
            // Then recurse into all values
            for (Object value : mapNode.values()) {
                String nested = findPromptValue(value, visited);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }

        if (node instanceof List<?> listNode) {
            for (Object item : listNode) {
                String nested = findPromptValue(item, visited);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    // ─── Utility methods ────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the string is a plausible identifier token of the
     * required length, containing only ASCII letters, digits, underscores, and hyphens.
     *
     * <p>Use this to validate extracted strings before treating them as IDs or codes
     * (e.g., market codes, store numbers) to reject free-form phrases.
     *
     * @param value     the trimmed string to validate; may be {@code null}
     * @param minLength the minimum acceptable length, inclusive
     * @param maxLength the maximum acceptable length, inclusive
     * @return {@code true} if the string is non-null, within the length bounds, and
     *         consists exclusively of letters, digits, {@code _}, and {@code -}
     */
    protected boolean isValidIdentifierToken(final String value, final int minLength, final int maxLength) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength || trimmed.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }
}
