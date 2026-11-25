package com.sskings.agendai.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, Membership.Key> {

    Optional<Membership> findByIdTenantIdAndIdUserId(UUID tenantId, UUID userId);

    /** "Meus estabelecimentos" numa consulta só (o RLS já restringe às participações do usuário). */
    @Query("""
            select new com.sskings.agendai.tenant.TenantService$TenantView(t.id, t.name, t.slug, t.timeZone, m.role)
            from Membership m join Tenant t on t.id = m.id.tenantId
            where m.id.userId = :user order by m.joinedAt""")
    List<TenantService.TenantView> viewsOf(@Param("user") UUID userId);
}
