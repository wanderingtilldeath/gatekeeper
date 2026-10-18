package com.gatekeeper.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Atomically flips used=false to used=true. Returns 0 if someone else got there first. */
    @Modifying(clearAutomatically = true)
    @Query("update RefreshToken t set t.used = true where t.id = :id and t.used = false")
    int markUsed(UUID id);

    @Modifying(clearAutomatically = true)
    @Query("delete from RefreshToken t where t.familyId = :familyId")
    void deleteFamily(UUID familyId);

    @Modifying(clearAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
