package com.gatekeeper.svc_java.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    @GetMapping("/java/health")
    public String health() {
        return "svc-java healthy ✅";
    }

    @GetMapping("/java/hello")
    public String hello() {
        return "Hello from svc-java";
    }
}
