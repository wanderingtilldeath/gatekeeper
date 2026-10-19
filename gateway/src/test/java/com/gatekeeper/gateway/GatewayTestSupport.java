package com.gatekeeper.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;

/**
 * One WireMock server plays both the auth service (JWKS) and the downstream backend.
 * No Docker or Redis needed: Redis points at a closed port (fail-open) or the limiter is mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.cloud.config.enabled=false")
@AutoConfigureWebTestClient
@ActiveProfiles("test")
abstract class GatewayTestSupport {

    static final String ISSUER = "https://gatekeeper.local";
    static final WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final RSAKey KEY_A = newKey("key-a");
    static final RSAKey KEY_B = newKey("key-b");

    static {
        wm.start();
    }

    @Autowired
    WebTestClient client;

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + wm.port();
        registry.add("SVC_JAVA_URI", () -> base);
        registry.add("SVC_GO_URI", () -> base);
        registry.add("AUTH_URI", () -> base);
        registry.add("JWKS_URI", () -> base + "/auth/.well-known/jwks.json");
    }

    @BeforeEach
    void resetDownstream() {
        wm.resetAll();
        publishJwks(KEY_A);
    }

    static RSAKey newKey(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void publishJwks(RSAKey... keys) {
        JWKSet set = new JWKSet(List.of(keys)).toPublicJWKSet();
        wm.stubFor(get("/auth/.well-known/jwks.json").willReturn(okJson(set.toString())));
    }

    static String token(RSAKey key, String sub) {
        return token(key, sub, Instant.now().plus(Duration.ofMinutes(15)), ISSUER);
    }

    static String tenantToken(RSAKey key, String sub, String tenant) {
        return token(key, sub, Instant.now().plus(Duration.ofMinutes(15)), ISSUER, tenant);
    }

    static String token(RSAKey key, String sub, Instant expires, String issuer) {
        return token(key, sub, expires, issuer, null);
    }

    static String token(RSAKey key, String sub, Instant expires, String issuer, String tenant) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .claim("tenant", tenant)
                    .issuer(issuer).subject(sub)
                    .issueTime(new Date()).expirationTime(Date.from(expires))
                    .claim("roles", List.of("USER", "ADMIN"))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request WireMock received for this path (the first one). */
    static ServeEvent received(String path) {
        return wm.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith(path))
                .reduce((first, second) -> second) // list is newest-first
                .orElseThrow(() -> new AssertionError("downstream never received " + path));
    }

    static int count(String method, String path) {
        return wm.findAll(WireMock.requestedFor(method, WireMock.urlPathEqualTo(path))).size();
    }
}
