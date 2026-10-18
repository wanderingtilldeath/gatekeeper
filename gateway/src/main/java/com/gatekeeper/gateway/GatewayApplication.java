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

	/** Bucket per client IP, for unauthenticated routes such as login. X-Forwarded-For is ignored on purpose (spoofable). */
	@Bean
	public KeyResolver ipKeyResolver() {
		return exchange -> Mono.just(remoteIp(exchange.getRequest().getRemoteAddress()));
	}

	private static String remoteIp(InetSocketAddress address) {
		return address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress();
	}
}
