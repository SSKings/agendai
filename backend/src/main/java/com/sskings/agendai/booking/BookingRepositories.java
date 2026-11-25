package com.sskings.agendai.booking;

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

// Toda consulta filtra por tenant_id (2ª camada do ADR-006), mesmo com o RLS (3ª) por baixo. A agenda é por dia de
// início: um agendamento cabe inteiro numa faixa de expediente, que nunca atravessa a meia-noite local. Já "o que
// ocupa" precisa pegar o que começou antes de "from" e ainda não acabou: "since" = from menos a duração máxima.

interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Os não cancelados primeiro: cancelados acumulados nunca empurram um agendamento ativo para fora do teto da lista. */
    String ACTIVE_FIRST = "case when b.status = com.sskings.agendai.booking.BookingStatus.CANCELLED then 1 else 0 end, b.startsAt, b.id";

    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);

    /** Muda o status só se ainda estiver confirmado: dois pedidos simultâneos não sobrescrevem um ao outro. */
    @Modifying
    @Query("""
            update Booking b set b.status = :status, b.updatedAt = :now
            where b.id = :id and b.tenantId = :tenant and b.status = com.sskings.agendai.booking.BookingStatus.CONFIRMED""")
    int closeIfConfirmed(@Param("tenant") UUID tenantId, @Param("id") UUID id, @Param("status") BookingStatus status, @Param("now") Instant now);

    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.startsAt >= :from and b.startsAt < :to
            order by\s""" + ACTIVE_FIRST)
    List<Booking> agenda(@Param("tenant") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.professionalId = :professional
            and b.startsAt >= :from and b.startsAt < :to
            order by\s""" + ACTIVE_FIRST)
    List<Booking> agendaOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId,
                           @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    /** O que ocupa o profissional em [from, to): os status de {@link BookingStatus#OCCUPYING}. */
    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.professionalId = :professional and b.status in :occupying
            and b.startsAt >= :since and b.startsAt < :to and b.blockedUntil > :from""")
    List<Booking> occupying(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId,
                            @Param("occupying") java.util.Collection<BookingStatus> occupying, @Param("since") Instant since,
                            @Param("from") Instant from, @Param("to") Instant to);
}

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** Cria o cliente se o telefone ainda não existe no estabelecimento; seguro com pedidos simultâneos. */
    @Modifying
    @Query(value = """
            INSERT INTO customers (id, tenant_id, name, phone, email, created_at)
            VALUES (:id, :tenant, :name, :phone, cast(:email as varchar), :now)
            ON CONFLICT (tenant_id, phone) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenant") UUID tenantId, @Param("name") String name, @Param("phone") String phone,
                       @Param("email") String email, @Param("now") Instant now);

    Optional<Customer> findByTenantIdAndPhone(UUID tenantId, String phone);

    List<Customer> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
