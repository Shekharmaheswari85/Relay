/*
 * Copyright 2026 Shekhar Maheswari.
 * All rights reserved.
 *
 * This source code is private and proprietary until an explicit open-source
 * license is published with this project.
 */
package io.relay.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import io.relay.model.BaseAgentSession;
import io.relay.repository.BaseAgentSessionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class that drives periodic expiry of idle agent sessions.
 *
 * <p>Sessions whose {@code updatedAt} timestamp predates the configured cutoff
 * are transitioned to the expired status and saved back to the repository. This
 * prevents stale sessions from accumulating in the database indefinitely and
 * ensures that downstream storage, chat history, and tool caches can be cleaned
 * up in a bounded fashion.
 *
 * <p>Subclasses must provide the expiry window, the set of statuses that are
 * eligible for expiration, and the status value to write on expiry. The
 * {@link org.springframework.scheduling.annotation.Scheduled} trigger is also
 * declared by the subclass so that applications can configure the check interval
 * independently of the library.
 *
 * <h3>Minimal concrete implementation</h3>
 * <pre>{@code
 * @Component
 * public class MySessionExpiryScheduler extends BaseSessionExpiryScheduler<MySessionDO> {
 *
 *     @Value("${agent.session.expiry-hours:24}")
 *     private long sessionExpiryHours;
 *
 *     public MySessionExpiryScheduler(MySessionRepository repository) {
 *         super(repository);
 *     }
 *
 *     @Override
 *     protected long getSessionExpiryHours() {
 *         return sessionExpiryHours;
 *     }
 *
 *     @Override
 *     protected List<String> getExpirableStatuses() {
 *         return List.of("ACTIVE", "PAUSED");
 *     }
 *
 *     @Override
 *     protected String getExpiredStatus() {
 *         return "EXPIRED";
 *     }
 *
 *     @Scheduled(fixedDelayString = "${agent.session.expiry-check-interval-ms:3600000}")
 *     public void runExpiry() {
 *         expireInactiveSessions();
 *     }
 * }
 * }</pre>
 *
 * <p>Override {@link #onSessionExpired} to add custom post-expiry cleanup such as
 * releasing SSE sinks, evicting caches, or notifying downstream services.
 *
 * @param <S> the concrete session entity type, which must extend
 *            {@link io.relay.model.BaseAgentSession}
 */
@Slf4j
public abstract class BaseSessionExpiryScheduler<S extends BaseAgentSession> {

    protected final BaseAgentSessionRepository<S> sessionRepository;

    /**
     * Constructs the scheduler with the repository used to load and persist
     * sessions during the expiry sweep.
     *
     * @param sessionRepository the repository for the session entity type;
     *                          must not be {@code null}
     */
    protected BaseSessionExpiryScheduler(final BaseAgentSessionRepository<S> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Returns the number of hours of inactivity after which a session is
     * considered stale and eligible for expiry.
     *
     * <p>A session is considered inactive when its {@code updatedAt} timestamp
     * is older than {@code LocalDateTime.now().minusHours(getSessionExpiryHours())}.
     *
     * @return positive number of hours; for example {@code 24} to expire sessions
     *         idle for more than one day
     */
    protected abstract long getSessionExpiryHours();

    /**
     * Returns the status string values that identify sessions eligible for
     * expiration.
     *
     * <p>Typically {@code List.of("ACTIVE", "PAUSED")}. Sessions already in a
     * terminal status ({@code COMPLETED}, {@code FAILED}, {@code EXPIRED}) must
     * not be included; including them is harmless but wastes a database query.
     *
     * @return non-null, non-empty list of status strings to query for
     */
    protected abstract List<String> getExpirableStatuses();

    /**
     * Returns the status string to write to a session when it is expired by
     * this scheduler.
     *
     * <p>Typically {@code "EXPIRED"}. The value written must be recognized by the
     * application's session status handling logic as a terminal state.
     *
     * @return the expired-status string; must not be {@code null}
     */
    protected abstract String getExpiredStatus();

    /**
     * Called once for each session transitioned to the expired status during a
     * sweep. The default implementation logs the session ID and last-updated
     * timestamp at INFO level.
     *
     * <p>Override to perform additional cleanup, such as closing SSE sinks,
     * evicting tool-result caches, or sending expiry notifications.
     *
     * @param session the session that was just marked expired and saved; never
     *                {@code null}
     */
    protected void onSessionExpired(final S session) {
        log.info("Expired session {} (lastUpdated={})", session.getSessionId(), session.getUpdatedAt());
    }

    /**
     * Queries for all sessions in an expirable status whose {@code updatedAt}
     * timestamp predates the configured cutoff, marks each one as expired, saves
     * it, and calls {@link #onSessionExpired}.
     *
     * <p>Call this method from the {@link org.springframework.scheduling.annotation.Scheduled}
     * trigger method in the concrete subclass. The method is intentionally
     * non-reactive: it executes on the scheduler thread (typically a virtual thread
     * or platform thread depending on the {@code @Scheduled} executor configuration)
     * and performs a synchronous JPA bulk query followed by per-record saves.
     *
     * <p>This method logs at DEBUG level when no stale sessions are found, and
     * at INFO level when sessions are expired, making it safe to run frequently
     * without producing excessive log noise.
     */
    public void expireInactiveSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(getSessionExpiryHours());
        List<S> stale = sessionRepository.findByStatusInAndUpdatedAtBefore(getExpirableStatuses(), cutoff);

        if (stale.isEmpty()) {
            log.debug("Session expiry check: no stale sessions found (cutoff={})", cutoff);
            return;
        }

        log.info("Expiring {} stale sessions with no activity since {}", stale.size(), cutoff);
        stale.forEach(session -> {
            session.setStatus(getExpiredStatus());
            sessionRepository.save(session);
            onSessionExpired(session);
        });
    }
}
