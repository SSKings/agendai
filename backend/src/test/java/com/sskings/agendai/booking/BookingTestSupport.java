package com.sskings.agendai.booking;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** Acesso de teste ao que a API não mostra (status interno, hash do código), como dono das tabelas. */
@TestComponent
public class BookingTestSupport {

    @Autowired PendingExpiry expiry;

    private final JdbcTemplate owner;

    public BookingTestSupport(@Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres) {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(), "agendai_owner", "owner-test");
        this.owner = new JdbcTemplate(ds);
    }

    public int expireAll() {
        return expiry.run();
    }

    /** Status do agendamento das {@code hhmm} (hora local de São Paulo) do dia 30/11 no estabelecimento; null se não há. */
    public String statusOf(String tenantId, String hhmm) {
        List<String> s = owner.queryForList("""
                SELECT status FROM bookings WHERE tenant_id = ? AND starts_at = (timestamp '2026-11-30 %s' AT TIME ZONE 'America/Sao_Paulo')
                ORDER BY created_at DESC LIMIT 1""".formatted(hhmm), String.class, UUID.fromString(tenantId));
        return s.isEmpty() ? null : s.get(0);
    }

    public String codeHashOf(String tenantId) {
        return owner.queryForObject("SELECT code_hash FROM bookings WHERE tenant_id = ? AND source = 'PUBLIC' LIMIT 1", String.class,
                UUID.fromString(tenantId));
    }

    public void setAttempts(String tenantId, String hhmm, int attempts) {
        owner.update("""
                UPDATE bookings SET code_attempts = ? WHERE tenant_id = ? AND starts_at = (timestamp '2026-11-30 %s' AT TIME ZONE 'America/Sao_Paulo')
                """.formatted(hhmm), attempts, UUID.fromString(tenantId));
    }

    public int attemptsOf(String tenantId, String hhmm) {
        return owner.queryForObject("""
                SELECT code_attempts FROM bookings WHERE tenant_id = ? AND starts_at = (timestamp '2026-11-30 %s' AT TIME ZONE 'America/Sao_Paulo')
                ORDER BY created_at DESC LIMIT 1""".formatted(hhmm), Integer.class, UUID.fromString(tenantId));
    }

    /** {@code n} agendamentos da página criados agora (já expirados), copiando o profissional/serviço/cliente de um existente ou criando dados mínimos. */
    public void fillPublic(String tenantId, int n) {
        fill(tenantId, n, "EXPIRED", "now() - interval '1 hour'");
    }

    /** {@code n} pendentes da página ainda valendo, em horários que não colidem (dezembro). */
    public void fillOpenPending(String tenantId, int n) {
        fill(tenantId, n, "PENDING", "now() + interval '1 day'");
    }

    private void fill(String tenantId, int n, String status, String pendingUntil) {
        UUID t = UUID.fromString(tenantId);
        UUID prof = owner.queryForObject("SELECT id FROM professionals WHERE tenant_id = ? LIMIT 1", UUID.class, t);
        UUID svc = owner.queryForObject("SELECT id FROM services WHERE tenant_id = ? LIMIT 1", UUID.class, t);
        owner.update("INSERT INTO customers (id, tenant_id, name, phone, created_at) VALUES (gen_random_uuid(), ?, 'Robô', '11911112222', now()) "
                + "ON CONFLICT DO NOTHING", t);
        UUID cust = owner.queryForObject("SELECT id FROM customers WHERE tenant_id = ? AND phone = '11911112222'", UUID.class, t);
        owner.update("""
                INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until, status,
                                      price_cents, created_at, updated_at, source, pending_until, code_hash, access_hash)
                SELECT gen_random_uuid(), ?, ?, ?, ?, timestamptz '2026-12-10 12:00Z' + (g || ' minutes')::interval,
                       timestamptz '2026-12-10 12:01Z' + (g || ' minutes')::interval, timestamptz '2026-12-10 12:01Z' + (g || ' minutes')::interval,
                       ?, 0, ?, now(), 'PUBLIC', %s, md5(random()::text), md5(random()::text) || g
                FROM generate_series(1, ?) g""".formatted(pendingUntil),
                t, prof, svc, cust, status, java.sql.Timestamp.from(clock.instant()), n);
    }

    @Autowired java.time.Clock clock;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.sskings.agendai.db.DbSession db;
    @Autowired BookingRepository bookings;

    /** Dentro do estabelecimento {@code tenantId}, conta uma tentativa e fecha o pendente das hhmm pedindo pelo estabelecimento {@code asTenant}. */
    public List<Integer> attemptAndCloseAs(String tenantId, String asTenant, String hhmm) {
        UUID id = owner.queryForObject("""
                SELECT id FROM bookings WHERE tenant_id = ? AND starts_at = (timestamp '2026-11-30 %s' AT TIME ZONE 'America/Sao_Paulo')
                ORDER BY created_at DESC LIMIT 1""".formatted(hhmm), UUID.class, UUID.fromString(tenantId));
        return tx.execute(s -> {
            db.enterTenant(UUID.fromString(tenantId));
            return List.of(bookings.countAttempt(UUID.fromString(asTenant), id, 5),
                    bookings.closePending(UUID.fromString(asTenant), id, BookingStatus.EXPIRED, clock.instant()));
        });
    }
}
