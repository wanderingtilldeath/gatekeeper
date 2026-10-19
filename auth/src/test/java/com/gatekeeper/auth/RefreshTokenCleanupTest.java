package com.gatekeeper.auth;

import com.gatekeeper.auth.domain.RefreshToken;
import com.gatekeeper.auth.domain.RefreshTokenRepository;
import com.gatekeeper.auth.domain.User;
import com.gatekeeper.auth.domain.UserRepository;
import com.gatekeeper.auth.token.RefreshTokenCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RefreshTokenCleanupTest {

    @Autowired RefreshTokenCleanup cleanup;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired ApplicationContext context;

    private RefreshToken token(User user, Duration expiresIn) {
        return tokens.save(new RefreshToken(user.getId(), UUID.randomUUID(), UUID.randomUUID().toString(),
                Instant.now().plus(expiresIn)));
    }

    @Test
    void deletesLongExpiredTokensAndKeepsTheRest() {
        User user = users.save(new User("cleanup" + UUID.randomUUID().toString().substring(0, 8), "hash", "USER"));
        RefreshToken longExpired = token(user, Duration.ofDays(-3));
        RefreshToken justExpired = token(user, Duration.ofHours(-1)); // inside the one-day margin
        RefreshToken live = token(user, Duration.ofDays(5));

        assertThat(cleanup.purgeExpired()).isGreaterThanOrEqualTo(1);

        assertThat(tokens.findById(longExpired.getId())).isEmpty();
        assertThat(tokens.findById(justExpired.getId())).isPresent();
        assertThat(tokens.findById(live.getId())).isPresent();
    }

    @Test
    void jobIsActuallyScheduled() {
        // @Scheduled does nothing unless scheduling is enabled
        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .anyMatch(task -> task.toString().contains("RefreshTokenCleanup.purgeExpired"));
    }
}
