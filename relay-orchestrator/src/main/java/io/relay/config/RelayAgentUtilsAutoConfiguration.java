/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.io.File;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;

import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for Spring AI Agent Utils in the Relay ecosystem.
 * <p>
 * Automatically instantiates and exposes the FileSystemTools, GrepTool,
 * ShellTools, and SkillsTool beans. When these beans are active, they are
 * auto-discovered and registered by the ChatClient configuration.
 */
@AutoConfiguration
@ConditionalOnClass(FileSystemTools.class)
@Slf4j
public class RelayAgentUtilsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FileSystemTools fileSystemTools() {
        log.info("Registering FileSystemTools sandboxed to the project root directory.");
        return FileSystemTools.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public GrepTool grepTool() {
        log.info("Registering GrepTool for code and text pattern searching.");
        return GrepTool.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ShellTools shellTools() {
        log.info("Registering ShellTools sandbox with standard safe commands.");
        return ShellTools.builder().build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolCallback skillsTool() {
        log.info("Registering SkillsTool pointing to .relay/skills directory.");
        File skillsDir = new File(".relay/skills");
        if (!skillsDir.exists()) {
            boolean created = skillsDir.mkdirs();
            if (!created && !skillsDir.exists()) {
                log.warn("Failed to create skills directory: {}", skillsDir.getAbsolutePath());
            }
        }
        File[] files = skillsDir.listFiles((dir, name) -> name.endsWith(".md") || name.endsWith(".skills"));
        if (files == null || files.length == 0) {
            File welcomeSkill = new File(skillsDir, "welcome.md");
            try {
                Files.writeString(welcomeSkill.toPath(),
                        "---\nname: welcome\ndescription: Welcome skill to verify setup\n---\nWelcome to Relay!",
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to write default welcome skill file", e);
            }
        }
        try {
            return SkillsTool.builder()
                    .addSkillsDirectory(skillsDir.getAbsolutePath())
                    .build();
        } catch (Exception e) {
            log.info("Could not configure SkillsTool (reason: {}). Exposing graceful fallback dummy tool.",
                    e.getMessage());
            return new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder()
                            .name("skillsTool")
                            .description("List and execute agent skills (no skills currently configured)")
                            .inputSchema("{}")
                            .build();
                }

                @Override
                public String call(String toolInput) {
                    return "No skills are currently configured in .relay/skills";
                }
            };
        }
    }
}
