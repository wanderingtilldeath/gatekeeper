package com.gatekeeper.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/auth/login", "/auth/register", "/auth/refresh").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    /** Verifies RS256 tokens locally against the auth service's JWKS (see {@link ThrottledJwkSource}). */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(@Value("${gateway.jwt.jwks-uri}") String jwksUri,
                                         @Value("${gateway.jwt.issuer}") String issuer,
                                         @Value("${gateway.jwt.cache-ttl:5m}") Duration cacheTtl,
                                         @Value("${gateway.jwt.min-refresh-interval:30s}") Duration minRefresh) {
        var keySource = new ThrottledJwkSource(WebClient.create(), jwksUri, cacheTtl, minRefresh, Duration.ofSeconds(3));
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(keySource)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
