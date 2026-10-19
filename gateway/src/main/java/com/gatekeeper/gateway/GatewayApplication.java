package com.gatekeeper.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

	/** Rate-limit bucket per authenticated user (JWT sub), so minting new tokens doesn't reset the bucket. */
	@Bean
	@Primary
	public KeyResolver userKeyResolver() {
		return exchange -> exchange.getPrincipal().map(Principal::getName)
				.switchIfEmpty(Mono.fromSupplier(() -> remoteIp(exchange.getRequest().getRemoteAddress())));
	}

	/**
	 * Per-IP buckets for the unauthenticated /auth routes. The Redis limiter keys its bucket by this value alone
	 * (not by route), so each route gets its own prefix; otherwise login and register would drain one shared bucket.
	 * X-Forwarded-For is ignored on purpose (spoofable).
	 */
	@Bean
	public KeyResolver loginKeyResolver() {
		return ipKeyResolver("login:");
	}

	@Bean
	public KeyResolver authKeyResolver() {
		return ipKeyResolver("auth:");
	}

	private static KeyResolver ipKeyResolver(String prefix) {
		return exchange -> Mono.just(prefix + remoteIp(exchange.getRequest().getRemoteAddress()));
	}

	private static String remoteIp(InetSocketAddress address) {
		return address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress();
	}
}
