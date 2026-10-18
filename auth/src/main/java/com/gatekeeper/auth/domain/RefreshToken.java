package com.gatekeeper.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** All tokens descended from one login share a family, so reuse can revoke the lot. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    /** SHA-256 of the token; the raw token is never stored. */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used;

    protected RefreshToken() {}

    public RefreshToken(UUID userId, UUID familyId, String tokenHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isUsed() { return used; }
}
