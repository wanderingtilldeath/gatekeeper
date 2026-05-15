package com.gatekeeper.gateway.fallback;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


@RestController
public class FallbackController {

    @GetMapping("/fallback/java")
    public Mono<String> javaFallback() {
        return Mono.just("svc-java is temporarily unavailable. Please try later.");
    }

    @GetMapping("/auth")
    public Mono<String> authFallback() {
        return Mono.just("Auth service is down. Please try again later.");
    }
}
