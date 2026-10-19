package com.gatekeeper.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Comma-separated, e.g. "USER,ADMIN". */
    @Column(nullable = false)
    private String roles;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {}

    public User(String username, String passwordHash, String roles) {
        this(username, passwordHash, roles, "default");
    }

    public User(String username, String passwordHash, String roles, String tenantId) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getTenantId() { return tenantId; }
    public List<String> getRoleList() { return List.of(roles.split(",")); }
}
