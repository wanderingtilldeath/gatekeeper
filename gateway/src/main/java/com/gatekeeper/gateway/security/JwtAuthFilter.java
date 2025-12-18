package com.gatekeeper.gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthFilter implements WebFilter {

    private final WebClient webClient;

    public JwtAuthFilter(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();
        System.out.println("➡️ Gateway path: " + path);

        if (path.startsWith("/auth") || path.startsWith("/actuator")) {
            System.out.println("✅ Auth path bypassed");
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        System.out.println("➡️ Auth header: " + authHeader);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.out.println("❌ Missing or invalid Authorization header");
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        System.out.println("➡️ Token extracted");

        return webClient.get()
                .uri("http://auth/auth/validate?token={token}", token)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(username ->
                        System.out.println("➡️ Username from auth service: [" + username + "]")
                )
                .flatMap(username -> {
                    if (username == null || username.isBlank()) {
                        System.out.println("❌ Username invalid → rejecting");
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }

                    System.out.println("✅ Token valid → forwarding request");
                    return chain.filter(exchange);
                })
                .onErrorResume(ex -> {
                    System.out.println("❌ Error calling auth service: " + ex.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                });
    }
}


