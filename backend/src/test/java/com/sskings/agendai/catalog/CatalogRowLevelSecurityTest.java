package com.sskings.agendai.catalog;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static com.sskings.agendai.catalog.CatalogIntegrationTest.professional;
import static com.sskings.agendai.catalog.CatalogIntegrationTest.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 3ª camada do ADR-006 nas tabelas do catálogo, sem o guard nem o filtro das consultas (como se houvesse um bug). */
class CatalogRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    record Fixture(Session ana, Session eve, String anaService, String anaProfessional, String eveProfessional, String eveService) {}

    private Fixture fixture() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String svc = body(call(post("/api/tenants/" + ana.tenantId() + "/services"), ana.token(), service("Corte", 30, 0))).get("id").asString();
        String pro = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals"), ana.token(), professional("João", List.of(svc))))
                .get("id").asString();
        String evePro = body(call(post("/api/tenants/" + eve.tenantId() + "/professionals"), eve.token(), professional("Eva", List.of())))
                .get("id").asString();
        String eveSvc = body(call(post("/api/tenants/" + eve.tenantId() + "/services"), eve.token(), service("Unha", 30, 0))).get("id").asString();
        return new Fixture(ana, eve, svc, pro, evePro, eveSvc);
    }

    private <T> T asEve(Fixture f, org.springframework.transaction.support.TransactionCallback<T> work) {
        return tx.execute(s -> {
            db.actAs(UUID.fromString(f.eve().userId()));
            db.enterTenant(UUID.fromString(f.eve().tenantId()));
            return work.doInTransaction(s);
        });
    }

    @Test
    @DisplayName("Consulta sem filtro dentro do estabelecimento da Eve não enxerga nada da Ana, e UPDATE/DELETE não afetam")
    void foreignRowsInvisible() throws Exception {
        Fixture f = fixture();
        List<?> counts = asEve(f, s -> List.of(
                em.createNativeQuery("SELECT count(*) FROM services").getSingleResult(),
                em.createNativeQuery("SELECT count(*) FROM professionals").getSingleResult(),
                em.createNativeQuery("SELECT count(*) FROM professional_services").getSingleResult(),
                em.createNativeQuery("UPDATE services SET name = 'x'").executeUpdate(),
                em.createNativeQuery("DELETE FROM professional_services").executeUpdate(),
                em.createNativeQuery("DELETE FROM professionals WHERE id <> cast(:p as uuid)").setParameter("p", f.eveProfessional()).executeUpdate()));
        assertThat(counts).extracting(Object::toString).containsExactly("1", "1", "0", "1", "0", "0");
    }

    @Test
    @DisplayName("Inserir linha com o tenant_id de outro estabelecimento é barrado pelo RLS")
    void cannotInsertIntoForeignTenant() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(() -> asEve(f, s -> em.createNativeQuery("""
                INSERT INTO services (id, tenant_id, name, duration_minutes, buffer_minutes, price_cents, active, created_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), 'Invasão', 30, 0, 0, true, now())""")
                .setParameter("t", f.ana().tenantId()).executeUpdate())).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("WITH CHECK também em professionals e professional_services: linha com tenant alheio é barrada")
    void cannotInsertProfessionalOrLinkIntoForeignTenant() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(() -> asEve(f, s -> em.createNativeQuery("""
                INSERT INTO professionals (id, tenant_id, name, active, created_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), 'Invasor', true, now())""")
                .setParameter("t", f.ana().tenantId()).executeUpdate())).hasMessageContaining("row-level security");
        assertThatThrownBy(() -> asEve(f, s -> em.createNativeQuery("""
                INSERT INTO professional_services (tenant_id, professional_id, service_id)
                VALUES (cast(:t as uuid), cast(:p as uuid), cast(:s as uuid))""")
                .setParameter("t", f.ana().tenantId()).setParameter("p", f.anaProfessional()).setParameter("s", f.anaService())
                .executeUpdate())).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("Ligar profissional da Ana a serviço da Eve (lado inverso) também é impossível (FK composta)")
    void compositeForeignKeyOtherSide() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(() -> asEve(f, s -> em.createNativeQuery("""
                INSERT INTO professional_services (tenant_id, professional_id, service_id)
                VALUES (cast(:t as uuid), cast(:p as uuid), cast(:s as uuid))""")
                .setParameter("t", f.eve().tenantId()).setParameter("p", f.anaProfessional()).setParameter("s", f.eveService())
                .executeUpdate())).hasMessageContaining("professional_services_professional_id_tenant_id_fkey");
    }

    @Test
    @DisplayName("Ligar profissional da Eve a serviço da Ana é impossível no banco (FK composta com o tenant_id)")
    void compositeForeignKey() throws Exception {
        Fixture f = fixture();
        assertThatThrownBy(() -> asEve(f, s -> em.createNativeQuery("""
                INSERT INTO professional_services (tenant_id, professional_id, service_id)
                VALUES (cast(:t as uuid), cast(:p as uuid), cast(:s as uuid))""")
                .setParameter("t", f.eve().tenantId()).setParameter("p", f.eveProfessional()).setParameter("s", f.anaService())
                .executeUpdate())).hasMessageContaining("professional_services_service_id_tenant_id_fkey");
    }
}
