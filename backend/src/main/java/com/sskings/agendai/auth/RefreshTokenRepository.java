package com.sskings.agendai.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Linha travada: dois refresh simultâneos com o mesmo token não geram duas sessões. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :hash")
    Optional<RefreshToken> findByHashForUpdate(@Param("hash") String hash);

    // Só os ainda utilizáveis: os usados ou vencidos já não valem, e regravá-los a cada logout cresceria com o histórico.
    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.familyId = :family and r.revokedAt is null and r.usedAt is null and r.expiresAt > :now")
    int revokeFamily(@Param("family") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.userId = :user and r.revokedAt is null and r.usedAt is null and r.expiresAt > :now")
    int revokeAllOf(@Param("user") UUID userId, @Param("now") Instant now);
}
