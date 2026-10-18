package com.gatekeeper.gateway.fallback;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Target of the circuit breaker's forward: answers 503 JSON for every HTTP method. */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service) {
        return ResponseEntity.status(503).body(Map.of(
                "error", "Service Unavailable",
                "message", service + " is temporarily unavailable, please retry shortly"));
    }
}
