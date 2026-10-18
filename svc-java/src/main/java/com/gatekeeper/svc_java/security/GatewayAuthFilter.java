package com.gatekeeper.svc_java.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Rejects any request that did not come through the gateway (it adds X-Gateway-Token). Health stays open for probes. */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public GatewayAuthFilter(@Value("${gateway.shared-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String provided = request.getHeader("X-Gateway-Token");
        boolean ok = provided != null
                && MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8)); // constant-time

        if (!ok) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Forbidden\",\"message\":\"Direct access is not allowed\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
