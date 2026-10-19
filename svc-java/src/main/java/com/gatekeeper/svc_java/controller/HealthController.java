package com.gatekeeper.svc_java.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
                                      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        Map<String, String> body = new HashMap<>();
        body.put("service", "svc-java");
        body.put("userId", userId);
        body.put("roles", roles);
        body.put("tenantId", tenantId);
        body.put("requestId", requestId);
        return body;
    }
}
