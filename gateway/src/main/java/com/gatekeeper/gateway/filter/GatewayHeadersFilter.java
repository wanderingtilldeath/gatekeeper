package com.gatekeeper.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs on every routed request:
 * 1. drops any client-supplied X-User-* header (a client must not be able to claim an identity),
 * 2. sets X-User-Id / X-User-Roles from the verified JWT,
 * 3. sets a fresh X-Request-Id (sent downstream and echoed back),
 * 4. adds Retry-After to 429 responses.
 */
@Component
public class GatewayHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";
    public static final String REQUEST_ID = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(auth -> Optional.of(((JwtAuthenticationToken) auth).getToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(jwt -> chain.filter(decorate(exchange, jwt)));
    }

    private ServerWebExchange decorate(ServerWebExchange exchange, Optional<Jwt> jwt) {
        String requestId = UUID.randomUUID().toString();
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.keySet().removeIf(name -> name.toLowerCase(Locale.ROOT).startsWith("x-user-"));
            headers.set(REQUEST_ID, requestId);
            jwt.ifPresent(token -> {
                headers.set(USER_ID, token.getSubject());
                List<String> roles = token.getClaimAsStringList("roles");
                if (roles != null) {
                    headers.set(USER_ROLES, String.join(",", roles));
                }
            });
        }).build();

        var response = exchange.getResponse();
        response.getHeaders().set(REQUEST_ID, requestId);
        response.beforeCommit(() -> {
            if (response.getStatusCode() != null && response.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                    && !response.getHeaders().containsKey("Retry-After")) {
                response.getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds(response)));
            }
            return Mono.empty();
        });
        return exchange.mutate().request(request).build();
    }

    /** Seconds until the bucket can pay for one request again: requested tokens / refill rate, at least 1. */
    private static long retryAfterSeconds(org.springframework.http.server.reactive.ServerHttpResponse response) {
        long rate = header(response, "X-RateLimit-Replenish-Rate", 1);
        long cost = header(response, "X-RateLimit-Requested-Tokens", 1);
        return Math.max(1, (long) Math.ceil((double) cost / Math.max(1, rate)));
    }

    private static long header(org.springframework.http.server.reactive.ServerHttpResponse response, String name, long fallback) {
        try {
            return Long.parseLong(response.getHeaders().getFirst(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
