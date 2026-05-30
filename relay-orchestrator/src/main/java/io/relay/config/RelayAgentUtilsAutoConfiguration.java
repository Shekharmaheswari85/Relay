/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.config;

import java.io.File;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallback;
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
            skillsDir.mkdirs();
        }
        return SkillsTool.builder()
                .addSkillsDirectory(".relay/skills")
                .build();
    }
}
