/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.memory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Highly resilient, file-based implementation of {@link AgentMemoryManager}
 * that persists
 * {@link MemoryEntry} units to a sandboxed local directory using Jackson
 * {@link ObjectMapper}.
 *
 * <p>
 * Unlike the default in-memory concurrent map manager, this implementation
 * persists all
 * memories across JVM restarts and server redeployments, fulfilling key agentic
 * memory durability
 * guidelines.
 *
 * <p>
 * Directories are structured hierarchically:
 * <ul>
 * <li>{@code <memories-root>/sessions/<sessionId>/<type>/<timestamp>_<uuid>.json}</li>
 * <li>{@code <memories-root>/users/<userId>/<type>/<timestamp>_<uuid>.json}</li>
 * </ul>
 *
 * <p>
 * Recall uses the same case-insensitive keyword token matching as
 * {@link InMemoryAgentMemoryManager}
 * for backwards compatibility and drop-in styling.
 */
@Slf4j
public class FileBasedAgentMemoryManager implements AgentMemoryManager {

    private final ObjectMapper objectMapper;
    private final String memoriesDir;

    public FileBasedAgentMemoryManager(
            final ObjectMapper objectMapper,
            @Value("${relay.memory.dir:#{systemProperties['user.home'] + '/.superagent/memory'}}") final String memoriesDir) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.memoriesDir = memoriesDir != null ? memoriesDir : System.getProperty("user.home") + "/.superagent/memory";
        ensureDirectoryExists(Paths.get(this.memoriesDir));
        log.info("Initialized FileBasedAgentMemoryManager with root directory: {}", this.memoriesDir);
    }

    @Override
    public synchronized void remember(final MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        String uuid = UUID.randomUUID().toString();
        long millis = entry.timestamp() != null ? entry.timestamp().toEpochMilli() : Instant.now().toEpochMilli();
        String filename = String.format("%d_%s.json", millis, uuid);

        try {
            if (entry.sessionId() != null) {
                Path dirPath = Paths.get(memoriesDir, "sessions", sanitizePath(entry.sessionId()), entry.type().name());
                ensureDirectoryExists(dirPath);
                Path filePath = dirPath.resolve(filename);
                objectMapper.writeValue(filePath.toFile(), entry);
            }
            if (entry.userId() != null) {
                Path dirPath = Paths.get(memoriesDir, "users", sanitizePath(entry.userId()), entry.type().name());
                ensureDirectoryExists(dirPath);
                Path filePath = dirPath.resolve(filename);
                objectMapper.writeValue(filePath.toFile(), entry);
            }
            log.debug("Memory persisted to file: type={} sessionId={} userId={}", entry.type(), entry.sessionId(),
                    entry.userId());
        } catch (IOException ex) {
            log.error("Failed to write memory entry to file: {}", ex.getMessage(), ex);
        }
    }

    @Override
    public List<MemoryEntry> recall(final String sessionId, final String userId, final MemoryType type,
            final String query, final int topK) {
        List<MemoryEntry> candidates = new ArrayList<>();

        if (sessionId != null) {
            Path dirPath = Paths.get(memoriesDir, "sessions", sanitizePath(sessionId), type.name());
            candidates.addAll(loadEntriesFromDir(dirPath));
        }

        if (userId != null) {
            Path dirPath = Paths.get(memoriesDir, "users", sanitizePath(userId), type.name());
            List<MemoryEntry> userEntries = loadEntriesFromDir(dirPath);
            userEntries.stream()
                    .filter(e -> !candidates.contains(e))
                    .forEach(candidates::add);
        }

        return candidates.stream()
                .filter(e -> matches(e, query))
                .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                .limit(Math.max(1, topK))
                .toList();
    }

    @Override
    public String assembleMemoryContext(final String sessionId, final String userId, final String query) {
        List<MemoryEntry> entries = recallForContext(sessionId, userId, query);
        if (entries.isEmpty()) {
            return "";
        }
        return MemoryContextUtil.assembleMemoryContext(entries);
    }

    @Override
    public synchronized void forget(final String sessionId, final MemoryType type) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(type, "MemoryType must not be null");
        Path dirPath = Paths.get(memoriesDir, "sessions", sanitizePath(sessionId), type.name());
        deleteDirectoryRecursively(dirPath);
        log.debug("Forgotten memories of type={} for sessionId={}", type, sessionId);
    }

    @Override
    public synchronized void forgetSession(final String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Path dirPath = Paths.get(memoriesDir, "sessions", sanitizePath(sessionId));
        deleteDirectoryRecursively(dirPath);
        log.debug("Wiped all memories for sessionId={}", sessionId);
    }

    @Override
    public synchronized void forgetUser(final String userId, final MemoryType type) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(type, "MemoryType must not be null");
        Path dirPath = Paths.get(memoriesDir, "users", sanitizePath(userId), type.name());
        deleteDirectoryRecursively(dirPath);
        log.debug("Forgotten user memories of type={} for userId={}", type, userId);
    }

    // ─── Resilient File Utilities ─────────────────────────────────────────────

    private List<MemoryEntry> loadEntriesFromDir(final Path dirPath) {
        List<MemoryEntry> entries = new ArrayList<>();
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            return entries;
        }

        try (Stream<Path> files = Files.list(dirPath)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(filePath -> {
                        try {
                            MemoryEntry entry = objectMapper.readValue(filePath.toFile(), MemoryEntry.class);
                            if (entry != null) {
                                entries.add(entry);
                            }
                        } catch (IOException e) {
                            log.warn("Resilient memory skip: Failed to read memory entry from {}: {}", filePath,
                                    e.getMessage());
                        }
                    });
        } catch (IOException ex) {
            log.error("Failed to list directory contents for {}: {}", dirPath, ex.getMessage());
        }
        return entries;
    }

    private void ensureDirectoryExists(final Path dirPath) {
        if (!Files.exists(dirPath)) {
            try {
                Files.createDirectories(dirPath);
            } catch (IOException ex) {
                log.error("Critical failure: Cannot create memories directory {}: {}", dirPath, ex.getMessage());
            }
        }
    }

    private void deleteDirectoryRecursively(final Path dirPath) {
        if (!Files.exists(dirPath)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dirPath)) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ex) {
            log.error("Failed to clean memories directory {}: {}", dirPath, ex.getMessage());
        }
    }

    private String sanitizePath(final String input) {
        if (input == null) {
            return "default";
        }
        return input.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private boolean matches(final MemoryEntry entry, final String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String lowerQuery = query.toLowerCase();
        String lowerContent = entry.content() != null ? entry.content().toLowerCase() : "";
        for (String token : lowerQuery.split("\\s+")) {
            if (token.length() > 3 && lowerContent.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
