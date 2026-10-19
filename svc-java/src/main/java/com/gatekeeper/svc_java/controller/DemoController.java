package com.gatekeeper.svc_java.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints that exist only to demo and test the gateway's timeout and circuit breaker.
 * Off unless demo.endpoints.enabled=true: otherwise any logged-in user could trip the breaker
 * for everyone (/fail) or tie up server threads (/slow).
 */
@RestController
@RequestMapping("/api/java")
@ConditionalOnProperty(name = "demo.endpoints.enabled", havingValue = "true")
public class DemoController {

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
