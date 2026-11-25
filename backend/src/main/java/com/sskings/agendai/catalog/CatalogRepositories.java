package com.sskings.agendai.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Toda consulta filtra por tenant_id (2ª camada do ADR-006), mesmo com o RLS (3ª) por baixo.

interface ServiceRepository extends JpaRepository<Service, UUID> {

    @Query("select s from Service s where s.tenantId = :tenant order by lower(s.name)")   // mesma expressão do índice
    List<Service> listOf(@Param("tenant") UUID tenantId);

    Optional<Service> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("select count(s) > 0 from Service s where s.tenantId = :tenant and lower(s.name) = lower(:name) and s.id <> :except")
    boolean nameTaken(@Param("tenant") UUID tenantId, @Param("name") String name, @Param("except") UUID except);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}

interface ProfessionalRepository extends JpaRepository<Professional, UUID> {

    @Query("select p from Professional p where p.tenantId = :tenant order by lower(p.name)")
    List<Professional> listOf(@Param("tenant") UUID tenantId);

    Optional<Professional> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("select count(p) > 0 from Professional p where p.tenantId = :tenant and lower(p.name) = lower(:name) and p.id <> :except")
    boolean nameTaken(@Param("tenant") UUID tenantId, @Param("name") String name, @Param("except") UUID except);

    long countByTenantId(UUID tenantId);

    /** Profissional ATIVO vinculado à conta: desativado, a conta deixa de agir pela agenda dele. */
    @Query("select p.id from Professional p where p.tenantId = :tenant and p.userId = :user and p.active = true")
    Optional<UUID> idOfUser(@Param("tenant") UUID tenantId, @Param("user") UUID userId);
}

interface ProfessionalServiceRepository extends JpaRepository<ProfessionalService, ProfessionalService.Link> {

    List<ProfessionalService> findByTenantId(UUID tenantId);

    boolean existsByTenantIdAndId(UUID tenantId, ProfessionalService.Link id);

    List<ProfessionalService> findByTenantIdAndIdProfessionalId(UUID tenantId, UUID professionalId);

    @Modifying
    @Query("delete from ProfessionalService ps where ps.tenantId = :tenant and ps.id.professionalId = :professional")
    void deleteOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId);
}
