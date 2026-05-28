/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.executor;

import org.springframework.stereotype.Repository;
import io.relay.repository.BaseAgentSessionRepository;

@Repository("agentSessionRepository")
public interface TestAgentSessionRepository extends BaseAgentSessionRepository<TestAgentSession> {
}
