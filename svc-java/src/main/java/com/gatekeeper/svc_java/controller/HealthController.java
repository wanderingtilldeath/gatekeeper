package com.gatekeeper.svc_java.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/java")
public class HealthController {

    @GetMapping("/health")
    public String health() {
        return "svc-java healthy ";
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello from svc-java";
    }

    /** Shows the identity the gateway put on the request, to prove it was propagated (and not client-supplied). */
    @GetMapping("/whoami")
    public Map<String, String> whoami(@RequestHeader(value = "X-User-Id", required = false) String userId,
                                      @RequestHeader(value = "X-User-Roles", required = false) String roles,
                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Map<String, String> body = new HashMap<>();
        body.put("service", "svc-java");
        body.put("userId", userId);
        body.put("roles", roles);
        body.put("requestId", requestId);
        return body;
    }

    /** Slow on purpose, to trigger the gateway's timeout: /api/java/slow?ms=5000 */
    @GetMapping("/slow")
    public String slow(@RequestParam(defaultValue = "5000") long ms) throws InterruptedException {
        Thread.sleep(Math.min(ms, 30_000));
        return "finally";
    }

    /** Always fails, to trip the gateway's circuit breaker. GET and POST so both retry rules can be shown. */
    @GetMapping("/fail")
    public String failGet() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "intentional failure");
    }

    @PostMapping("/fail")
    public String failPost() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "intentional failure");
    }
}
