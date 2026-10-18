package com.gatekeeper.auth.token;

import com.gatekeeper.auth.domain.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Every login and every refresh adds a row, and used rows must stay until they expire (reuse detection needs them).
 * Without this job the table only grows. Safe to run on several auth instances at once: the delete is idempotent.
 */
@Component
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);
    /** Expired tokens are useless at once; the extra day is only a margin. */
    static final Duration KEEP_AFTER_EXPIRY = Duration.ofDays(1);

    private final RefreshTokenRepository refreshTokens;

    public RefreshTokenCleanup(RefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public int purgeExpired() {
        int deleted = refreshTokens.deleteExpiredBefore(Instant.now().minus(KEEP_AFTER_EXPIRY));
        log.info("Deleted {} expired refresh tokens", deleted);
        return deleted;
    }
}
