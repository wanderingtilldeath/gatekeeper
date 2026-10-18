package com.gatekeeper.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("auth.jwt")
public record JwtProperties(String privateKey, Path keyFile, String issuer, Duration accessTtl, Duration refreshTtl) {}
