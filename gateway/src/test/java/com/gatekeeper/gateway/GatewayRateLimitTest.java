package com.gatekeeper.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The limiter itself is Spring's; these tests pin down what we added: key choice, 429 shape, Retry-After. */
class GatewayRateLimitTest extends GatewayTestSupport {

    @MockitoBean
    RedisRateLimiter limiter;

    @Test
    void overLimitGets429WithRetryAfterAndNeverReachesDownstream() {
        wm.stubFor(get("/api/java/hello").willReturn(aResponse().withStatus(200)));
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(new RateLimiter.Response(false,
                Map.of("X-RateLimit-Replenish-Rate", "1", "X-RateLimit-Requested-Tokens", "10"))));

        client.get().uri("/api/java/hello").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY_A, "user-1"))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "10");

        assertThat(count("GET", "/api/java/hello")).isZero();
    }

    @Test
    void bucketIsKeyedByUserIdNotByToken() {
        wm.stubFor(get("/api/java/hello").willReturn(aResponse().withStatus(200)));
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));

        // two different tokens for the same user must land in the same bucket
        for (int i = 0; i < 2; i++) {
            client.get().uri("/api/java/hello")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY_A, "user-42"))
                    .exchange().expectStatus().isOk();
        }
        verify(limiter, org.mockito.Mockito.times(2)).isAllowed(eq("svc-java"), eq("user-42"));
    }

    @Test
    void loginIsLimitedPerIpWithoutAToken() {
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(new RateLimiter.Response(false, Map.of())));

        client.post().uri("/auth/login").header("Content-Type", "application/json").bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");

        verify(limiter).isAllowed(eq("auth-login"), any());
    }
}
