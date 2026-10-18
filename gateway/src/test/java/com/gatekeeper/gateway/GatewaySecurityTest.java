package com.gatekeeper.gateway;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityTest extends GatewayTestSupport {

    private static final String SUB = "7d3b8f0e-1111-2222-3333-444455556666";

    @BeforeEach
    void stubDownstream() {
        wm.stubFor(get("/api/java/whoami").willReturn(aResponse().withStatus(200).withBody("ok")));
        wm.stubFor(post("/auth/login").willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private WebTestClientCall call(String bearer) {
        return new WebTestClientCall(bearer);
    }

    private class WebTestClientCall {
        private final String bearer;
        WebTestClientCall(String bearer) { this.bearer = bearer; }

        org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec get() {
            var spec = client.get().uri("/api/java/whoami");
            if (bearer != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
            return spec.exchange();
        }
    }

    @Test
    void noTokenIs401AndNeverReachesDownstream() {
        call(null).get().expectStatus().isUnauthorized();
        assertThat(count("GET", "/api/java/whoami")).isZero();
    }

    @Test
    void validTokenIsAcceptedAndIdentityIsPropagated() {
        call(token(KEY_A, SUB)).get().expectStatus().isOk()
                .expectHeader().exists("X-Request-Id");

        var headers = received("/api/java/whoami").getRequest().getHeaders();
        assertThat(headers.getHeader("X-User-Id").values()).containsExactly(SUB);
        assertThat(headers.getHeader("X-User-Roles").values()).containsExactly("USER,ADMIN");
        assertThat(headers.getHeader("X-Request-Id").isPresent()).isTrue();
    }

    @Test
    void expiredTokenIs401() {
        String expired = token(KEY_A, SUB, Instant.now().minus(Duration.ofMinutes(10)), ISSUER);
        call(expired).get().expectStatus().isUnauthorized();
    }

    @Test
    void tamperedTokenIs401() {
        String good = token(KEY_A, SUB);
        String[] parts = good.split("\\.");
        // swap the payload for a different subject but keep the old signature
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"" + ISSUER + "\",\"sub\":\"admin\",\"exp\":" + (Instant.now().getEpochSecond() + 900) + "}").getBytes());
        call(parts[0] + "." + forgedPayload + "." + parts[2]).get().expectStatus().isUnauthorized();
    }

    @Test
    void tokenSignedWithAnotherKeyButSameKidIs401() {
        RSAKey imposter = newKey("key-a");
        call(token(imposter, SUB)).get().expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromWrongIssuerIs401() {
        call(token(KEY_A, SUB, Instant.now().plus(Duration.ofMinutes(5)), "https://evil.example")).get()
                .expectStatus().isUnauthorized();
    }

    @Test
    void clientSuppliedUserHeadersAreReplacedOrDropped() {
        client.get().uri("/api/java/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY_A, SUB))
                .header("X-User-Id", "admin")
                .header("X-User-Roles", "SUPERUSER")
                .header("x-user-extra", "evil")
                .header("X-Gateway-Token", "evil")
                .exchange().expectStatus().isOk();

        var headers = received("/api/java/whoami").getRequest().getHeaders();
        assertThat(headers.getHeader("X-User-Id").values()).containsExactly(SUB);
        assertThat(headers.getHeader("X-User-Roles").values()).containsExactly("USER,ADMIN");
        assertThat(headers.getHeader("X-User-Extra").isPresent()).isFalse();
        assertThat(headers.getHeader("X-Gateway-Token").values()).containsExactly("dev-only-gateway-secret");
    }

    @Test
    void spoofedHeadersAreStrippedOnPublicRoutesToo() {
        client.post().uri("/auth/login").header("X-User-Id", "admin").header("Content-Type", "application/json")
                .bodyValue("{}").exchange().expectStatus().isOk();

        assertThat(received("/auth/login").getRequest().getHeaders().getHeader("X-User-Id").isPresent()).isFalse();
    }

    @Test
    void publicAuthRoutesNeedNoTokenButOtherAuthPathsDo() {
        client.get().uri("/auth/validate?token=abc").exchange().expectStatus().isUnauthorized();
        client.get().uri("/auth/anything").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void gatewayAdminEndpointsAreNotReachable() {
        client.get().uri("/actuator/gateway/routes").exchange().expectStatus().isUnauthorized();
        client.post().uri("/actuator/gateway/routes/evil").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void keyRotationIsPickedUpWithoutRestart() {
        String tokenFromNewKey = token(KEY_B, SUB);
        call(tokenFromNewKey).get().expectStatus().isUnauthorized(); // auth hasn't published key-b yet

        publishJwks(KEY_A, KEY_B); // auth rotates
        call(tokenFromNewKey).get().expectStatus().isOk();
        call(token(KEY_A, SUB)).get().expectStatus().isOk(); // old key still valid during overlap
    }
}
