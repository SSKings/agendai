package com.sskings.agendai.schedule;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.sskings.agendai.schedule.ScheduleIntegrationTest.interval;
import static com.sskings.agendai.schedule.ScheduleIntegrationTest.timeOff;
import static com.sskings.agendai.schedule.ScheduleIntegrationTest.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** 3ª camada do ADR-006 nas tabelas da agenda, sem o guard nem o filtro das consultas (como se houvesse um bug). */
class ScheduleRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    record Setup(Session ana, Session eve, String anaProfessional, String eveProfessional) {}

    private Setup setup() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String joao = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals"), ana.token(),
                Map.of("name", "João", "active", true, "serviceIds", List.of()))).get("id").asString();
        String eva = body(call(post("/api/tenants/" + eve.tenantId() + "/professionals"), eve.token(),
                Map.of("name", "Eva", "active", true, "serviceIds", List.of()))).get("id").asString();
        call(put("/api/tenants/" + ana.tenantId() + "/professionals/" + joao + "/working-hours"), ana.token(),
                week(interval(1, "09:00", "12:00")));
        call(post("/api/tenants/" + ana.tenantId() + "/time-off"), ana.token(), timeOff(joao, "2026-12-01T09:00", "2026-12-01T10:00", null));
        call(post("/api/tenants/" + ana.tenantId() + "/time-off"), ana.token(), timeOff(null, "2026-12-25T00:00", "2026-12-26T00:00", null));
        return new Setup(ana, eve, joao, eva);
    }

    private <T> T asEve(Setup s, TransactionCallback<T> work) {
        return tx.execute(status -> {
            db.actAs(UUID.fromString(s.eve().userId()));
            db.enterTenant(UUID.fromString(s.eve().tenantId()));
            return work.doInTransaction(status);
        });
    }

    @Test
    @DisplayName("Consulta sem filtro dentro do estabelecimento da Eve não enxerga o expediente nem os bloqueios da Ana")
    void foreignRowsInvisible() throws Exception {
        Setup s = setup();
        List<?> counts = asEve(s, st -> List.of(
                em.createNativeQuery("SELECT count(*) FROM working_hours").getSingleResult(),
                em.createNativeQuery("SELECT count(*) FROM time_off").getSingleResult(),
                em.createNativeQuery("UPDATE working_hours SET end_minute = 1440").executeUpdate(),
                em.createNativeQuery("DELETE FROM time_off").executeUpdate()));
        assertThat(counts).extracting(Object::toString).containsExactly("0", "0", "0", "0");
    }

    @Test
    @DisplayName("Inserir expediente ou bloqueio com o tenant_id de outro estabelecimento é barrado pelo RLS")
    void cannotInsertIntoForeignTenant() throws Exception {
        Setup s = setup();
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO working_hours (id, tenant_id, professional_id, weekday, start_minute, end_minute)
                VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), 1, 0, 60)""")
                .setParameter("t", s.ana().tenantId()).setParameter("p", s.anaProfessional()).executeUpdate()))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO time_off (id, tenant_id, start_at, end_at, created_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), now(), now() + interval '1 hour', now())""")
                .setParameter("t", s.ana().tenantId()).executeUpdate()))
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("Expediente ou bloqueio da Eve apontando para profissional da Ana é impossível (FK composta)")
    void compositeForeignKeys() throws Exception {
        Setup s = setup();
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO working_hours (id, tenant_id, professional_id, weekday, start_minute, end_minute)
                VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), 1, 0, 60)""")
                .setParameter("t", s.eve().tenantId()).setParameter("p", s.anaProfessional()).executeUpdate()))
                .hasMessageContaining("working_hours_professional_id_tenant_id_fkey");
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO time_off (id, tenant_id, professional_id, start_at, end_at, created_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), now(), now() + interval '1 hour', now())""")
                .setParameter("t", s.eve().tenantId()).setParameter("p", s.anaProfessional()).executeUpdate()))
                .hasMessageContaining("time_off_professional_id_tenant_id_fkey");
    }

    @Test
    @DisplayName("O banco também recusa faixa invertida, fora do dia ou fora do passo de 5 min, e bloqueio com fim antes do início")
    void checkConstraints() throws Exception {
        Setup s = setup();
        for (String range : new String[]{"600, 600", "600, 540", "-5, 60", "0, 1445", "1, 60", "0, 61"}) {
            assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery(
                    "INSERT INTO working_hours (id, tenant_id, professional_id, weekday, start_minute, end_minute) "
                    + "VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), 1, " + range + ")")
                    .setParameter("t", s.eve().tenantId()).setParameter("p", s.eveProfessional()).executeUpdate()))
                    .as(range).hasMessageContaining("working_hours_check");
        }
        for (String day : new String[]{"0", "8"}) {
            assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery(
                    "INSERT INTO working_hours (id, tenant_id, professional_id, weekday, start_minute, end_minute) "
                    + "VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), " + day + ", 0, 60)")
                    .setParameter("t", s.eve().tenantId()).setParameter("p", s.eveProfessional()).executeUpdate()))
                    .hasMessageContaining("working_hours_weekday_check");
        }
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO time_off (id, tenant_id, start_at, end_at, created_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), now(), now(), now())""")
                .setParameter("t", s.eve().tenantId()).executeUpdate()))
                .hasMessageContaining("time_off_check");
    }
}
