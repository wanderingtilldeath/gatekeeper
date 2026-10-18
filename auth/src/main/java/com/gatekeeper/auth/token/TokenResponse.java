package com.gatekeeper.auth.token;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {}
