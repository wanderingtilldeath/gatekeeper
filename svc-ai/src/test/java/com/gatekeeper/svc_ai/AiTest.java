package com.gatekeeper.svc_ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class AiTest {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
    Map<String, String> store = new HashMap<>();
    Events events = mock(Events.class);
    AiProps props = new AiProps(50, false, 2, Set.of("delete_record"), Set.of("admin"),
            Map.of("support-bot", Set.of("read_record"), "cleaner-bot", Set.of("read_record", "delete_record")), 0.2, 1000);

    @BeforeEach
    void fakeRedis() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1))).when(ops).set(anyString(), anyString(), any(java.time.Duration.class));
        when(ops.increment(anyString())).thenAnswer(i -> inc(i.getArgument(0), 1));
        when(ops.increment(anyString(), anyLong())).thenAnswer(i -> inc(i.getArgument(0), i.<Long>getArgument(1)));
    }

    long inc(String k, long by) {
        long v = Long.parseLong(store.getOrDefault(k, "0")) + by;
        store.put(k, String.valueOf(v));
        return v;
    }

    ChatService chat(LlmProvider... ps) { return new ChatService(redis, List.of(ps), props, events); }

    @Test
    void redactsEmailCardAndKeyBeforeProviderSeesThem() {
        String[] seen = new String[1];
        chat(p -> { seen[0] = p; return "ok"; })
                .chat("u", "mail bob@x.com card 4111 1111 1111 1111 key sk-abcdefghijklmnop1234");
        assertEquals("mail [EMAIL] card [CARD] key [SECRET]", seen[0]);
    }

    @Test
    void refusesOnceAllowanceIsUsedUp() {
        ChatService c = chat(p -> "ok");
        c.chat("u", "a".repeat(100)); // ~25 tokens
        c.chat("u", "b".repeat(100)); // ~50 total, at the limit
        var e = assertThrows(ResponseStatusException.class, () -> c.chat("u", "c".repeat(100)));
        assertEquals(429, e.getStatusCode().value());
    }

    @Test
    void repeatedQuestionIsCachedAndFree() {
        int[] calls = {0};
        ChatService c = chat(p -> { calls[0]++; return "ok"; });
        c.chat("u", "same");
        assertTrue(c.chat("u", "same").cached());
        assertEquals(1, calls[0]);
    }

    @Test
    void backupAnswersWhenPrimaryFails() {
        var r = chat(p -> { throw new IllegalStateException("down"); }, p -> "backup says hi").chat("u", "hi");
        assertEquals("backup says hi", r.answer());
    }

    @Test
    void agentActionsAreDeniedPendingOrExecuted() {
        ActionService a = new ActionService(redis, props, events);
        assertEquals("DENIED", a.act("u", "support-bot", "delete_record", "all").status());
        assertEquals("EXECUTED", a.act("u", "cleaner-bot", "read_record", "42").status());
        var pending = a.act("u", "cleaner-bot", "delete_record", "everything");
        assertEquals("PENDING", pending.status());
        verify(events).publish(eq("ACTION_PENDING"), eq("u"), anyString());
        assertEquals(1, a.audit("PENDING").size());
    }

    @Test
    void onlyAdminApprovesAndOnlyOnce() {
        ActionService a = new ActionService(redis, props, events);
        var p = a.act("u", "cleaner-bot", "delete_record", "x");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> a.decide("u", p.id(), true)).getStatusCode().value());
        assertEquals("EXECUTED", a.decide("admin", p.id(), true).status());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> a.decide("admin", p.id(), true)).getStatusCode().value());
    }

    @Test
    void agentSpeedLimit() {
        ActionService a = new ActionService(redis, props, events);
        a.act("u", "cleaner-bot", "read_record", "1");
        a.act("u", "cleaner-bot", "read_record", "2");
        assertEquals("RATE_LIMITED", a.act("u", "cleaner-bot", "read_record", "3").status());
    }
}
