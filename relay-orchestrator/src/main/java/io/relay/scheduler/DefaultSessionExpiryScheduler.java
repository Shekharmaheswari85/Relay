/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.scheduler;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;

import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;
import io.relay.session.SessionStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Framework-provided concrete session expiry scheduler that activates automatically
 * when {@code agent.session.expiry.enabled=true} and
 * {@code spring-boot-starter-data-jpa} is on the classpath.
 *
 * <p>Expires {@link SessionStatus#ACTIVE} and {@link SessionStatus#PAUSED} sessions
 * that have not been updated for longer than {@code agent.session.expiry.idle-hours}
 * (default: 24 hours). Expired sessions are transitioned to
 * {@link SessionStatus#EXPIRED} and saved back to the repository.
 *
 * <p>The check interval defaults to 1 hour and is configurable via
 * {@code agent.session.expiry.check-interval-ms}.
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * agent:
 *   session:
 *     expiry:
 *       enabled: true
 *       idle-hours: 24
 *       check-interval-ms: 3600000
 * }</pre>
 *
 * <h3>Overriding the default</h3>
 * <p>To use custom expiry behaviour, declare your own
 * {@link BaseSessionExpiryScheduler} bean — the auto-configuration will back off:
 * <pre>{@code
 * @Component
 * public class MySessionExpiryScheduler extends BaseSessionExpiryScheduler<MySessionDO> {
 *
 *     public MySessionExpiryScheduler(MySessionRepository repo) { super(repo); }
 *
 *     @Override protected long getSessionExpiryHours() { return 12; }
 *
 *     @Override protected List<String> getExpirableStatuses() {
 *         return List.of("ACTIVE");
 *     }
 *
 *     @Override protected String getExpiredStatus() { return "EXPIRED"; }
 *
 *     @Scheduled(fixedDelayString = "${agent.session.expiry.check-interval-ms:3600000}")
 *     public void runExpiry() { expireInactiveSessions(); }
 * }
 * }</pre>
 *
 * @see BaseSessionExpiryScheduler
 * @see io.relay.config.RelayAutoConfiguration
 */
@Slf4j
public class DefaultSessionExpiryScheduler extends BaseSessionExpiryScheduler<BaseAgentSession> {

    private final long idleHours;

    /**
     * Constructs the scheduler wired to the given session repository.
     *
     * <p>The unchecked cast from {@code BaseAgentSessionRepository<?>} to
     * {@code BaseAgentSessionRepository<BaseAgentSession>} is safe at runtime because
     * Java generics are erased and every concrete session entity extends
     * {@link BaseAgentSession}, which defines all fields accessed during expiry.
     *
     * @param repository the session repository to query and persist expiry state
     * @param idleHours  the number of inactive hours before a session is expired;
     *                   sourced from {@code agent.session.expiry.idle-hours}
     */
    @SuppressWarnings("unchecked")
    public DefaultSessionExpiryScheduler(
            final BaseAgentSessionRepository<?> repository,
            final long idleHours) {
        super((BaseAgentSessionRepository<BaseAgentSession>) repository);
        this.idleHours = idleHours;
        log.info("Session expiry scheduler initialised: idleHours={}", idleHours);
    }

    /**
     * Scheduled trigger that invokes the expiry sweep.
     *
     * <p>Runs at the interval defined by {@code agent.session.expiry.check-interval-ms}
     * (default: 3,600,000 ms = 1 hour). The delay is measured from the completion of
     * the previous run, so a slow sweep never causes overlapping executions.
     */
    @Scheduled(fixedDelayString = "${agent.session.expiry.check-interval-ms:3600000}")
    public void runExpiry() {
        expireInactiveSessions();
    }

    @Override
    protected long getSessionExpiryHours() {
        return idleHours;
    }

    @Override
    protected List<String> getExpirableStatuses() {
        return List.of(SessionStatus.ACTIVE.name(), SessionStatus.PAUSED.name());
    }

    @Override
    protected String getExpiredStatus() {
        return SessionStatus.EXPIRED.name();
    }
}
