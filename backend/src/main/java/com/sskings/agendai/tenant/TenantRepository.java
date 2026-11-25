package com.sskings.agendai.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    /** Função do banco (SECURITY DEFINER): o RLS esconde os outros estabelecimentos, mas o link precisa ser único. */
    @Query(value = "SELECT slug_taken(:slug)", nativeQuery = true)
    boolean slugTaken(@Param("slug") String slug);

    /** Função do banco (SECURITY DEFINER, V6): o id do estabelecimento de um link público, ou nada. */
    @Query(value = "SELECT tenant_by_slug(:slug)", nativeQuery = true)
    java.util.Optional<UUID> idBySlug(@Param("slug") String slug);

    @Query("select t.timeZone from Tenant t where t.id = :id")
    java.util.Optional<String> timeZoneOf(@Param("id") UUID id);
}
