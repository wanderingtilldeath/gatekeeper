package com.gatekeeper.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "gateway.limits.api.scope=tenant")
class GatewayTenantTest extends GatewayTestSupport {

    @MockitoBean
    RedisRateLimiter limiter;

    @Test
    void usersOfOneTenantShareABucketAndOtherTenantsDoNot() {
        wm.stubFor(get("/api/java/hello").willReturn(aResponse().withStatus(200)));
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));

        for (String[] caller : new String[][]{{"u1", "acme"}, {"u2", "acme"}, {"u3", "globex"}}) {
            client.get().uri("/api/java/hello")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken(KEY_A, caller[0], caller[1]))
                    .exchange().expectStatus().isOk();
        }
        verify(limiter, times(2)).isAllowed(eq("svc-java"), eq("tenant:acme"));
        verify(limiter).isAllowed(eq("svc-java"), eq("tenant:globex"));
    }

    @Test
    void tenantComesFromTheTokenNotFromAClientHeader() {
        wm.stubFor(get("/api/java/whoami").willReturn(aResponse().withStatus(200)));
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));

        client.get().uri("/api/java/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenantToken(KEY_A, "u1", "acme"))
                .header("X-Tenant-Id", "globex")
                .exchange().expectStatus().isOk();

        var headers = received("/api/java/whoami").getRequest().getHeaders();
        assertThat(headers.getHeader("X-Tenant-Id").values()).containsExactly("acme");
    }
}
