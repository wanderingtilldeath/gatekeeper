package com.gatekeeper.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/** Timeouts, retries and the circuit breaker. Redis is unreachable here, which doubles as the fail-open test. */
class GatewayResilienceTest extends GatewayTestSupport {

    @Autowired
    CircuitBreakerRegistry breakers;

    private String bearer;

    @BeforeEach
    void freshBreaker() {
        breakers.circuitBreaker("javaService").reset();
        bearer = "Bearer " + token(KEY_A, "user-1");
    }

    private WebTestClient.RequestHeadersSpec<?> authedGet(String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private WebTestClient.RequestHeadersSpec<?> authedPost(String path) {
        return client.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).bodyValue("{}");
    }

    @Test
    void redisDownFailsOpenSoRequestsStillGoThrough() {
        wm.stubFor(get("/api/java/ok").willReturn(aResponse().withStatus(200).withBody("fine")));

        authedGet("/api/java/ok").exchange().expectStatus().isOk();
    }

    @Test
    void slowDownstreamTimesOutAndReturnsJsonFallback() {
        wm.stubFor(get("/api/java/slow").willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        authedGet("/api/java/slow").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo("Service Unavailable");
    }

    @Test
    void failingDownstreamOpensBreakerAndStopsHittingIt() {
        wm.stubFor(post("/api/java/fail").willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 4; i++) { // window of 4 calls, all failures
            authedPost("/api/java/fail").exchange().expectStatus().isEqualTo(503);
        }
        assertThat(breakers.circuitBreaker("javaService").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int reachedBefore = count("POST", "/api/java/fail");
        for (int i = 0; i < 3; i++) {
            authedPost("/api/java/fail").exchange().expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.message").exists();
        }
        assertThat(count("POST", "/api/java/fail")).isEqualTo(reachedBefore); // breaker short-circuited
    }

    @Test
    void onlyGetRequestsAreRetried() {
        wm.stubFor(get("/api/java/flaky").willReturn(aResponse().withStatus(503)));
        wm.stubFor(post("/api/java/flaky").willReturn(aResponse().withStatus(503)));

        authedGet("/api/java/flaky").exchange().expectStatus().isEqualTo(503);
        authedPost("/api/java/flaky").exchange().expectStatus().isEqualTo(503);

        assertThat(count("GET", "/api/java/flaky")).isEqualTo(3);  // 1 try + 2 retries
        assertThat(count("POST", "/api/java/flaky")).isEqualTo(1); // never retried
    }

    @Test
    void goRouteForwardsIdentityAndSecret() {
        wm.stubFor(get("/api/go/whoami").willReturn(aResponse().withStatus(200)));

        authedGet("/api/go/whoami").exchange().expectStatus().isOk();

        var headers = received("/api/go/whoami").getRequest().getHeaders();
        assertThat(headers.getHeader("X-User-Id").values()).containsExactly("user-1");
        assertThat(headers.getHeader("X-Gateway-Token").values()).containsExactly("dev-only-gateway-secret");
    }
}
