package com.gatekeeper.svc_ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public record Reply(String answer, boolean cached, int tokensUsed) {}

    private final StringRedisTemplate redis;
    private final List<LlmProvider> providers; // @Order: primary first, then backup
    private final AiProps props;
    private final Events events;

    ChatService(StringRedisTemplate redis, List<LlmProvider> providers, AiProps props, Events events) {
        this.redis = redis; this.providers = providers; this.props = props; this.events = events;
    }

    public Reply chat(String user, String prompt) {
        String safe = Redactor.redact(prompt);
        String cacheKey = "ai:cache:" + sha256(safe);
        String hit = redis.opsForValue().get(cacheKey);
        if (hit != null) return new Reply(hit, true, 0); // repeated question: free, no allowance spent

        int tokens = estimateTokens(safe);
        long used = usedToday(user);
        if (used + tokens > props.dailyTokens()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "daily AI allowance used up");
        }

        String answer = callProviders(safe);
        redis.opsForValue().set(cacheKey, answer, Duration.ofHours(1));
        long after = redis.opsForValue().increment(budgetKey(user), tokens);
        redis.expire(budgetKey(user), Duration.ofDays(1));
        if (used * 5 < props.dailyTokens() * 4L && after * 5 >= props.dailyTokens() * 4L) {
            events.publish("BUDGET_80", user, after + "/" + props.dailyTokens() + " tokens used today");
        }
        return new Reply(answer, false, tokens);
    }

    public long usedToday(String user) {
        String v = redis.opsForValue().get(budgetKey(user));
        return v == null ? 0 : Long.parseLong(v);
    }

    String callProviders(String prompt) {
        for (LlmProvider p : providers) {
            try {
                return p.complete(prompt);
            } catch (Exception e) {
                log.warn("provider failed, trying next: {}", e.getMessage());
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "all AI providers are down");
    }

    // shortcut: ~4 chars per token, upgrade to the provider's reported usage when a real one is wired in
    static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private static String budgetKey(String user) {
        return "ai:budget:" + user + ":" + LocalDate.now();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
