package com.gatekeeper.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gatekeeper.gateway.filter.RequestLogFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class RequestLogFilterTest {
    @SuppressWarnings("unchecked")
    ReactiveListOperations<String, String> ops = mock(ReactiveListOperations.class);
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);

    RequestLogFilter filter() throws Exception {
        when(redis.opsForList()).thenReturn((ReactiveListOperations) ops);
        when(ops.leftPush(anyString(), anyString())).thenReturn(Mono.just(1L));
        when(ops.trim(anyString(), anyLong(), anyLong())).thenReturn(Mono.just(true));
        return new RequestLogFilter(redis);
    }

    @Test
    void recordsStatusAndErrorOfFailedRequest() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/ai/usage"));
        WebFilterChain chain = e -> Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to find instance for svc-ai"));
        assertThrows(ResponseStatusException.class, () -> filter().filter(exchange, chain).block());
        var json = ArgumentCaptor.forClass(String.class);
        verify(ops).leftPush(eq(RequestLogFilter.KEY), json.capture());
        assertTrue(json.getValue().contains("\"status\":503"));
        assertTrue(json.getValue().contains("Unable to find instance for svc-ai"));
    }

    @Test
    void redisFailureDoesNotBreakTheRequest() throws Exception {
        var f = filter();
        when(ops.leftPush(anyString(), anyString())).thenReturn(Mono.error(new IllegalStateException("redis down")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        f.filter(exchange, e -> Mono.empty()).block(); // must complete normally
    }
}
