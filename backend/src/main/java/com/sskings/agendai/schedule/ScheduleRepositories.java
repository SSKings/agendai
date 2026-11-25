package com.sskings.agendai.schedule;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Toda consulta filtra por tenant_id (2ª camada do ADR-006), mesmo com o RLS (3ª) por baixo.

interface WorkingHoursRepository extends JpaRepository<WorkingHours, UUID> {

    @Query("select w from WorkingHours w where w.tenantId = :tenant and w.professionalId = :professional order by w.weekday, w.startMinute")
    List<WorkingHours> weekOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId);

    @Query("""
            select w from WorkingHours w where w.tenantId = :tenant and w.professionalId = :professional and w.weekday = :weekday
            order by w.startMinute""")
    List<WorkingHours> dayOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId, @Param("weekday") int weekday);

    List<WorkingHours> findByTenantIdAndProfessionalIdIn(UUID tenantId, Collection<UUID> professionalIds);

    @Modifying
    @Query("delete from WorkingHours w where w.tenantId = :tenant and w.professionalId = :professional")
    void deleteOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId);
}

interface TimeOffRepository extends JpaRepository<TimeOff, UUID> {

    /**
     * Bloqueios que encostam em [from, to) — do estabelecimento todo e de qualquer profissional. {@code since} = from
     * menos a duração máxima de um bloqueio: limita a faixa lida do índice (tenant_id, start_at), por maior que seja o
     * histórico.
     */
    @Query("""
            select t from TimeOff t where t.tenantId = :tenant and t.startAt >= :since and t.startAt < :to and t.endAt > :from
            order by t.startAt, t.id""")
    List<TimeOff> overlapping(@Param("tenant") UUID tenantId, @Param("since") Instant since, @Param("from") Instant from,
                              @Param("to") Instant to, Limit limit);

    /** Bloqueios que tiram horários de um profissional em [from, to): os dele e os do estabelecimento inteiro. */
    @Query("""
            select t from TimeOff t where t.tenantId = :tenant and t.startAt >= :since and t.startAt < :to and t.endAt > :from
            and (t.professionalId = :professional or t.professionalId is null)""")
    List<TimeOff> blocking(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId,
                           @Param("since") Instant since, @Param("from") Instant from, @Param("to") Instant to);

    /** Como {@link #blocking}, para vários profissionais de uma vez (agenda da semana). */
    @Query("""
            select t from TimeOff t where t.tenantId = :tenant and t.startAt >= :since and t.startAt < :to and t.endAt > :from
            and (t.professionalId in :professionals or t.professionalId is null)""")
    List<TimeOff> blockingAny(@Param("tenant") UUID tenantId, @Param("professionals") Collection<UUID> professionalIds,
                              @Param("since") Instant since, @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    long countByTenantIdAndEndAtAfter(UUID tenantId, Instant now);

    Optional<TimeOff> findByIdAndTenantId(UUID id, UUID tenantId);
}
