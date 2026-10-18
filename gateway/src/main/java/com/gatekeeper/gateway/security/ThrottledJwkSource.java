package com.gatekeeper.gateway.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Supplies the public keys for token verification from the auth service's JWKS endpoint.
 * <ul>
 *   <li>Keys are cached and re-fetched after {@code ttl}.</li>
 *   <li>A token with an unknown {@code kid} triggers a re-fetch (that is how key rotation is picked up),
 *       but at most once per {@code minRefresh}. Without this, anyone could send tokens with random
 *       kids and make every request call the auth service.</li>
 *   <li>Concurrent callers share one in-flight fetch, and a fetch has a timeout.</li>
 *   <li>If a re-fetch fails, the keys already cached keep being used.</li>
 * </ul>
 * Consequence for rotation: publish the new key in the JWKS first, start signing with it later.
 */
final class ThrottledJwkSource implements Function<SignedJWT, Flux<JWK>> {

    private final WebClient webClient;
    private final String uri;
    private final Duration ttl;
    private final Duration minRefresh;
    private final Duration timeout;

    private volatile JWKSet keys;
    private volatile Instant fetchedAt = Instant.EPOCH;
    private Instant lastAttempt = Instant.EPOCH; // guarded by this
    private Mono<JWKSet> inflight;               // guarded by this

    ThrottledJwkSource(WebClient webClient, String uri, Duration ttl, Duration minRefresh, Duration timeout) {
        this.webClient = webClient;
        this.uri = uri;
        this.ttl = ttl;
        this.minRefresh = minRefresh;
        this.timeout = timeout;
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        JWKSet cached = keys;
        Instant now = Instant.now();

        if (cached != null && Duration.between(fetchedAt, now).compareTo(ttl) < 0) {
            List<JWK> match = selector.select(cached);
            if (!match.isEmpty()) {
                return Flux.fromIterable(match);
            }
        }
        List<JWK> fallback = cached == null ? List.of() : selector.select(cached);
        Mono<JWKSet> fetch = startOrJoinFetch(now);
        if (fetch == null) { // asked too recently: answer from what we have (unknown kid => no key => 401)
            return Flux.fromIterable(fallback);
        }
        return fetch.map(selector::select)
                .onErrorReturn(fallback)
                .flatMapMany(Flux::fromIterable);
    }

    private synchronized Mono<JWKSet> startOrJoinFetch(Instant now) {
        if (inflight != null) {
            return inflight;
        }
        if (Duration.between(lastAttempt, now).compareTo(minRefresh) < 0) {
            return null;
        }
        lastAttempt = now;
        inflight = webClient.get().uri(uri).retrieve().bodyToMono(String.class)
                .timeout(timeout)
                .map(ThrottledJwkSource::parse)
                .doOnNext(set -> {
                    keys = set;
                    fetchedAt = Instant.now();
                })
                .doFinally(signal -> clearInflight())
                .cache();
        return inflight;
    }

    private synchronized void clearInflight() {
        inflight = null;
    }

    private static JWKSet parse(String body) {
        try {
            return JWKSet.parse(body);
        } catch (ParseException e) {
            throw new IllegalStateException("JWKS endpoint returned something that is not a JWK set", e);
        }
    }
}
