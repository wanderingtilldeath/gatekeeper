package com.gatekeeper.svc_java.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String GATEWAY_SECRET = "GATEWAY_SECRET_123";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String gatewayHeader = request.getHeader("X-Gateway-Token");

        if (!GATEWAY_SECRET.equals(gatewayHeader)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.getWriter().write("Direct access forbidden");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
