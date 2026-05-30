/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.tool;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;

import lombok.extern.slf4j.Slf4j;

/**
 * Sandboxed agent memory tools that implement the Claude API / Claude Code
 * AutoMemoryTools specification
 * one-to-one, allowing the agent to dynamically explore, insert, create, edit,
 * or delete its own
 * persistent long-term memories in structured markdown files.
 *
 * <p>
 * Exposes 6 purpose-named operations sandboxed to
 * {@code <memories-root>/agentic_memories} directory:
 * <ul>
 * <li>{@code memoryView} — reads a file with line numbers or lists the
 * sandboxed directory structure</li>
 * <li>{@code memoryCreate} — creates a new memory file with YAML
 * frontmatter</li>
 * <li>{@code memoryStrReplace} — performs exact search-and-replace editing on
 * existing memory files</li>
 * <li>{@code memoryInsert} — appends/inserts text lines at specific locations
 * (essential for MEMORY.md index updates)</li>
 * <li>{@code memoryDelete} — deletes memory topic files</li>
 * <li>{@code memoryRename} — renames/moves memory files</li>
 * </ul>
 */
@AgentTool(category = ToolCategory.MUTATION, requiresSession = true)
@Slf4j
public class AutoMemoryTools {

    private final Path sandboxRoot;

    public AutoMemoryTools(
            @Value("${relay.memory.dir:#{systemProperties['user.home'] + '/.superagent/memory'}}") final String memoriesDir) {
        String baseDir = memoriesDir != null ? memoriesDir : System.getProperty("user.home") + "/.superagent/memory";
        this.sandboxRoot = Paths.get(baseDir, "agentic_memories").toAbsolutePath().normalize();
        ensureDirectoryExists(this.sandboxRoot);
        log.info("Initialized AutoMemoryTools with sandbox root: {}", this.sandboxRoot);
    }

    @Tool(description = "Reads a memory file with 1-based line numbers, or lists files in the memories directory up to 2 levels deep when path is a directory or blank.")
    public String memoryView(
            @ToolParam(description = "Relative path to a file or directory in memories (e.g. 'MEMORY.md', 'user_profile.md', or blank to list all)") final String path) {
        Path target = resolveAndVerifyPath(path);

        if (Files.isDirectory(target)) {
            return listDirectoryRecursively(target, 2);
        } else if (Files.isRegularFile(target)) {
            try {
                List<String> lines = Files.readAllLines(target);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.size(); i++) {
                    sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
                }
                return sb.toString();
            } catch (IOException e) {
                return "Error: Failed to read memory file: " + e.getMessage();
            }
        } else {
            return "Error: Path not found: " + path;
        }
    }

    @Tool(description = "Creates a new memory file with frontmatter and initial contents (Step 1 of two-step save).")
    public String memoryCreate(
            @ToolParam(description = "Relative path of the new memory file (e.g. 'user_profile.md')") final String path,
            @ToolParam(description = "Full text content for the file, including frontmatter") final String content) {
        Path target = resolveAndVerifyPath(path);

        if (Files.exists(target)) {
            return "Error: Memory file already exists at: " + path + ". Use memoryStrReplace to edit it.";
        }

        try {
            ensureDirectoryExists(target.getParent());
            Files.writeString(target, content != null ? content : "");
            return "Success: Created memory file at: " + path;
        } catch (IOException e) {
            return "Error: Failed to create memory file: " + e.getMessage();
        }
    }

    @Tool(description = "Edits a memory file by replacing an exact, unique target block of text with a replacement block.")
    public String memoryStrReplace(
            @ToolParam(description = "Relative path of the memory file to edit") final String path,
            @ToolParam(description = "The exact, unique block of text to be replaced") final String targetText,
            @ToolParam(description = "The new text to replace the target block") final String replacementText) {
        Path target = resolveAndVerifyPath(path);

        if (!Files.isRegularFile(target)) {
            return "Error: File not found at: " + path;
        }

        try {
            String original = Files.readString(target);
            if (!original.contains(targetText)) {
                return "Error: Target text block was not found in the file. Exact match required.";
            }

            int firstIndex = original.indexOf(targetText);
            int lastIndex = original.lastIndexOf(targetText);
            if (firstIndex != lastIndex) {
                return "Error: Multiple matches of target text block found. Please supply a larger, unique block of text to match.";
            }

            String updated = original.replace(targetText, replacementText != null ? replacementText : "");
            Files.writeString(target, updated);
            return "Success: Replaced text block in: " + path;
        } catch (IOException e) {
            return "Error: Failed to edit memory file: " + e.getMessage();
        }
    }

    @Tool(description = "Inserts text after a specific line number (1-indexed) in a memory file (useful for appending new links to MEMORY.md index).")
    public String memoryInsert(
            @ToolParam(description = "Relative path to the memory file (e.g. 'MEMORY.md')") final String path,
            @ToolParam(description = "Line number (1-based index) after which to insert the content") final int lineNumber,
            @ToolParam(description = "Text line or block of lines to insert") final String content) {
        Path target = resolveAndVerifyPath(path);

        if (!Files.isRegularFile(target)) {
            return "Error: File not found at: " + path;
        }

        try {
            List<String> lines = Files.readAllLines(target);
            int size = lines.size();

            // Allow inserting at line 0 (prepend) or at the end
            if (lineNumber < 0 || lineNumber > size) {
                return String.format("Error: Line number %d is out of bounds (file has %d lines).", lineNumber, size);
            }

            List<String> updated = new ArrayList<>(lines);
            // Splitting content by lines just in case multiple lines are passed
            String[] toInsert = content != null ? content.split("\\r?\\n") : new String[] { "" };

            int insertIndex = lineNumber;
            for (String insertLine : toInsert) {
                updated.add(insertIndex++, insertLine);
            }

            Files.write(target, updated);
            return String.format("Success: Inserted content after line %d in %s", lineNumber, path);
        } catch (IOException e) {
            return "Error: Failed to insert line: " + e.getMessage();
        }
    }

    @Tool(description = "Deletes a memory file or recursively deletes a topic directory.")
    public String memoryDelete(
            @ToolParam(description = "Relative path to delete") final String path) {
        Path target = resolveAndVerifyPath(path);

        if (!Files.exists(target)) {
            return "Error: Path does not exist: " + path;
        }

        if (target.equals(sandboxRoot)) {
            return "Error: Security block - Cannot delete the memories root directory itself.";
        }

        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
                return "Success: Recursively deleted memories directory: " + path;
            } else {
                Files.delete(target);
                return "Success: Deleted memory file: " + path;
            }
        } catch (IOException e) {
            return "Error: Failed to delete path: " + e.getMessage();
        }
    }

    @Tool(description = "Renames or moves a memory file. Updates the memory index link separately.")
    public String memoryRename(
            @ToolParam(description = "Relative source path of memory file") final String sourcePath,
            @ToolParam(description = "Relative destination path") final String destPath) {
        Path source = resolveAndVerifyPath(sourcePath);
        Path dest = resolveAndVerifyPath(destPath);

        if (!Files.exists(source)) {
            return "Error: Source path does not exist: " + sourcePath;
        }
        if (Files.exists(dest)) {
            return "Error: Destination path already exists: " + destPath;
        }

        try {
            ensureDirectoryExists(dest.getParent());
            Files.move(source, dest);
            return String.format("Success: Renamed memory from %s to %s", sourcePath, destPath);
        } catch (IOException e) {
            return "Error: Failed to rename memory: " + e.getMessage();
        }
    }

    // ─── Helpers & Path Traversal Guards ──────────────────────────────────────

    private Path resolveAndVerifyPath(final String relativePath) {
        String clean = relativePath != null ? relativePath.trim() : "";
        // Pre-sanitize against basic navigation strings
        while (clean.startsWith("/") || clean.startsWith("\\")) {
            clean = clean.substring(1);
        }

        Path resolved = sandboxRoot.resolve(clean).normalize().toAbsolutePath();

        // Trap directory traversal attacks
        if (!resolved.startsWith(sandboxRoot)) {
            log.warn("Security Alert: Direct traversal blocked for path: {}", relativePath);
            throw new IllegalArgumentException("Security block: Access denied to path outside sandbox.");
        }

        return resolved;
    }

    private void ensureDirectoryExists(final Path dirPath) {
        if (dirPath != null && !Files.exists(dirPath)) {
            try {
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                log.error("Failed to create sandbox directories {}: {}", dirPath, e.getMessage());
            }
        }
    }

    private String listDirectoryRecursively(final Path dir, final int maxDepth) {
        StringBuilder sb = new StringBuilder("Memories Directory Listing:\n");
        listDirectoryRecursivelyHelper(dir, maxDepth, 0, sb);
        return sb.toString();
    }

    private void listDirectoryRecursivelyHelper(final Path current, final int maxDepth, final int currentDepth,
            final StringBuilder sb) {
        if (currentDepth > maxDepth) {
            return;
        }

        try (Stream<Path> stream = Files.list(current)) {
            List<Path> paths = stream.sorted().toList();
            for (Path p : paths) {
                String indent = "  ".repeat(currentDepth);
                String relative = sandboxRoot.relativize(p).toString();
                if (Files.isDirectory(p)) {
                    sb.append(indent).append("📁 [Directory] ").append(relative).append("/\n");
                    listDirectoryRecursivelyHelper(p, maxDepth, currentDepth + 1, sb);
                } else {
                    sb.append(indent).append("📄 ").append(p.getFileName()).append(" (").append(relative).append(")\n");
                }
            }
        } catch (IOException e) {
            sb.append("Error listing folder: ").append(e.getMessage()).append("\n");
        }
    }
}
