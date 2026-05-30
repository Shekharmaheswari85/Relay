package io.relay.memory;

import java.util.List;

/**
 * Utility methods for memory manager implementations.
 */
public final class MemoryContextUtil {
    private MemoryContextUtil() {
        // utility class
    }

    /**
     * Builds the formatted [AGENT MEMORY] block from the given entries.
     *
     * @param entries list of memory entries, may be empty
     * @return formatted string or empty string if entries are empty
     */
    public static String assembleMemoryContext(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n[AGENT MEMORY]\n");
        for (MemoryEntry e : entries) {
            sb.append(e.type().name()).append(": ").append(e.content()).append("\n");
        }
        sb.append("[END AGENT MEMORY]\n");
        return sb.toString();
    }
}
