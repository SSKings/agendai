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

    /** Teto diário por origem: o que entra pela página nunca consome o teto do painel (e vice-versa). */
    long countByTenantIdAndSourceAndCreatedAtAfter(UUID tenantId, BookingSource source, Instant since);

    /** Pendentes ainda valendo no estabelecimento (teto: robô não enche a agenda de pendentes). */
    @Query("""
            select count(b) from Booking b where b.tenantId = :tenant
            and b.status = com.sskings.agendai.booking.BookingStatus.PENDING and b.pendingUntil > :now""")
    long countOpenPending(@Param("tenant") UUID tenantId, @Param("now") Instant now);

    @Query("select b.status from Booking b where b.tenantId = :tenant and b.id = :id")
    BookingStatus statusOf(@Param("tenant") UUID tenantId, @Param("id") UUID id);

    /** Muda o status só se ainda estiver confirmado: dois pedidos simultâneos não sobrescrevem um ao outro. */
    @Modifying
    @Query("""
            update Booking b set b.status = :status, b.updatedAt = :now
            where b.id = :id and b.tenantId = :tenant and b.status = com.sskings.agendai.booking.BookingStatus.CONFIRMED""")
    int closeIfConfirmed(@Param("tenant") UUID tenantId, @Param("id") UUID id, @Param("status") BookingStatus status, @Param("now") Instant now);

    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.startsAt >= :from and b.startsAt < :to
            and b.status <> com.sskings.agendai.booking.BookingStatus.EXPIRED
            order by\s""" + ACTIVE_FIRST)
    List<Booking> agenda(@Param("tenant") UUID tenantId, @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.professionalId = :professional
            and b.startsAt >= :from and b.startsAt < :to and b.status <> com.sskings.agendai.booking.BookingStatus.EXPIRED
            order by\s""" + ACTIVE_FIRST)
    List<Booking> agendaOf(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId,
                           @Param("from") Instant from, @Param("to") Instant to, Limit limit);

    /**
     * O que ocupa o profissional em [from, to): os status de {@link BookingStatus#OCCUPYING}, menos o pendente já vencido
     * (que o job ainda não expirou — ele não segura o horário).
     */
    @Query("""
            select b from Booking b where b.tenantId = :tenant and b.professionalId = :professional and b.status in :occupying
            and (b.status <> com.sskings.agendai.booking.BookingStatus.PENDING or b.pendingUntil > :now)
            and b.startsAt >= :since and b.startsAt < :to and b.blockedUntil > :from""")
    List<Booking> occupying(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId,
                            @Param("occupying") java.util.Collection<BookingStatus> occupying, @Param("now") Instant now,
                            @Param("since") Instant since, @Param("from") Instant from, @Param("to") Instant to);

    /** Pendentes vencidos do profissional viram EXPIRED antes de um agendamento novo (o job pode não ter passado ainda). */
    @Modifying
    @Query("""
            update Booking b set b.status = com.sskings.agendai.booking.BookingStatus.EXPIRED, b.codeHash = null, b.updatedAt = :now
            where b.tenantId = :tenant and b.professionalId = :professional
            and b.status = com.sskings.agendai.booking.BookingStatus.PENDING and b.pendingUntil <= :now""")
    int expireStale(@Param("tenant") UUID tenantId, @Param("professional") UUID professionalId, @Param("now") Instant now);

    Optional<Booking> findByTenantIdAndAccessHash(UUID tenantId, String accessHash);

    /** Na remarcação, o token passa para o agendamento novo (índice único): o antigo deixa de responder por ele. */
    @Modifying
    @Query(value = "UPDATE bookings SET access_hash = NULL WHERE tenant_id = :tenant AND id = :id", nativeQuery = true)
    int clearAccess(@Param("tenant") UUID tenantId, @Param("id") UUID id);

    /** Conta uma tentativa do código, só enquanto houver tentativas: com pedidos simultâneos, nunca passa de 5. */
    @Modifying
    @Query(value = "UPDATE bookings SET code_attempts = code_attempts + 1 WHERE tenant_id = :tenant AND id = :id AND code_attempts < :max",
            nativeQuery = true)
    int countAttempt(@Param("tenant") UUID tenantId, @Param("id") UUID id, @Param("max") int max);

    @Modifying
    @Query("""
            update Booking b set b.status = :status, b.codeHash = null, b.updatedAt = :now
            where b.tenantId = :tenant and b.id = :id and b.status = com.sskings.agendai.booking.BookingStatus.PENDING""")
    int closePending(@Param("tenant") UUID tenantId, @Param("id") UUID id, @Param("status") BookingStatus status, @Param("now") Instant now);

    /** Função do banco (SECURITY DEFINER, V7): expira os pendentes vencidos de todos os estabelecimentos. */
    @Query(value = "SELECT expire_pending_bookings(:now)", nativeQuery = true)
    int expireAllPending(@Param("now") Instant now);
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
