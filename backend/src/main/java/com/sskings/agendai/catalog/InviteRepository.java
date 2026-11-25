package com.sskings.agendai.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface InviteRepository extends JpaRepository<Invite, UUID> {

    /** Revoga os convites ainda válidos do profissional (um novo convite, ou o dono cancelou). */
    @Modifying
    @Query(value = """
            UPDATE invites SET revoked_at = :now
            WHERE tenant_id = :tenant AND professional_id = :professional AND used_at IS NULL AND revoked_at IS NULL AND expires_at > :now""",
            nativeQuery = true)
    int revokeActive(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId, @Param("now") Instant now);

    /** Função SECURITY DEFINER (V5): [estabelecimento, 'OK' | 'INVALID' | 'MEMBER']. */
    @Query(value = "SELECT CAST(tenant AS text), outcome FROM redeem_invite(:hash, :now)", nativeQuery = true)
    List<Object[]> redeem(@Param("hash") String tokenHash, @Param("now") Instant now);
}
