package com.gatekeeper.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup when GATEWAY_SECRET is missing or empty. Route filters resolve the secret lazily,
 * so without this the gateway would start and then send a broken token to the backends.
 */
@Component
class RequiredSecretCheck {

    RequiredSecretCheck(@Value("${gateway.shared-secret}") String secret) {
        if (secret.isBlank()) {
            throw new IllegalStateException("GATEWAY_SECRET must not be empty");
        }
    }
}
