/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.executor;

import java.util.Map;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import io.relay.model.BaseAgentSession;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "test_agent_sessions")
@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class TestAgentSession extends BaseAgentSession {

    @Override
    public Map<String, Object> getDomainContext() {
        return Map.of("testContextKey", "testContextValue");
    }
}
