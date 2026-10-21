package com.gatekeeper.svc_ai;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ConfigurationProperties(prefix = "ai")
public record AiProps(int dailyTokens, boolean primaryFails, int agentPerMinute,
                      Set<String> risky, Set<String> admins, Map<String, Set<String>> agents,
                      double incidentRate, long incidentSlowMs) {

    public void requireAdmin(String user) {
        if (!admins.contains(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admins only");
    }
}
