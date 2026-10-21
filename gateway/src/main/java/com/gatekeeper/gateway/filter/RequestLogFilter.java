package com.gatekeeper.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Records one line per request (service, status, latency, short error) so svc-ai can explain an outage.
 * Runs just inside RequestIdFilter so it also sees 401/403 and routing errors. No bodies or headers are stored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLogFilter implements WebFilter {
    public static final String KEY = "incidents:requests";
    static final int MAX = 5000;
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequestLogFilter(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange)
                .doOnError(ex -> record(exchange, start, ex))
                .then(Mono.fromRunnable(() -> record(exchange, start, null)));
    }

    private void record(ServerWebExchange exchange, long start, Throwable ex) {
        try {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            HttpStatusCode code = exchange.getResponse().getStatusCode();
            int status = ex instanceof ResponseStatusException r ? r.getStatusCode().value()
                    : ex != null ? 500 : code != null ? code.value() : 200;
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toEpochMilli());
            rec.put("requestId", exchange.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER));
            rec.put("service", route != null ? route.getId() : "unrouted");
            rec.put("path", exchange.getRequest().getURI().getPath());
            rec.put("status", status);
            rec.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
            rec.put("error", ex == null ? null : shortError(ex));
            String json = mapper.writeValueAsString(rec);
            // fire and forget: logging must never slow down or fail the request
            redis.opsForList().leftPush(KEY, json)
                    .then(redis.opsForList().trim(KEY, 0, MAX - 1))
                    .subscribe(ok -> {}, e -> log.warn("request log failed: {}", e.toString()));
        } catch (Exception e) {
            log.warn("request log failed: {}", e.toString());
        }
    }

    static String shortError(Throwable ex) {
        String msg = ex instanceof ResponseStatusException r && r.getReason() != null ? r.getReason() : ex.getMessage();
        String s = ex.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
