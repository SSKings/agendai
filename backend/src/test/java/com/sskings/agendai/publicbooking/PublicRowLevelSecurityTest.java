package com.sskings.agendai.publicbooking;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A transação anônima da página pública, no banco: a função do link só dá o id; o RLS faz o resto. */
class PublicRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("Sem usuário nem estabelecimento, nada aparece; tenant_by_slug só devolve o id; dentro de um, só os dados dele")
    void anonymousTransaction() throws Exception {
        Session ana = register("Ana", email(), "Loja Anônima A");
        Session eve = register("Eve", email(), "Loja Anônima B");
        for (Session s : List.of(ana, eve)) {
            call(post("/api/tenants/" + s.tenantId() + "/services"), s.token(), Map.of("name", "Corte", "durationMinutes", 30,
                    "bufferMinutes", 0, "priceCents", 0, "active", true));
        }
        String slug = body(call(get("/api/me"), ana.token(), null)).get("tenants").get(0).get("slug").asString();
        List<?> out = tx.execute(s -> List.of(
                em.createNativeQuery("SELECT count(*) FROM tenants").getSingleResult(),
                em.createNativeQuery("SELECT count(*) FROM services").getSingleResult(),
                em.createNativeQuery("SELECT CAST(tenant_by_slug(:s) AS text)").setParameter("s", slug).getSingleResult()));
        assertThat(out.get(0).toString()).as("sem estabelecimento na transação, nenhum").isEqualTo("0");
        assertThat(out.get(1).toString()).isEqualTo("0");
        assertThat(out.get(2)).isEqualTo(ana.tenantId());
        Object services = tx.execute(s -> {
            db.enterTenant(UUID.fromString(ana.tenantId()));
            return em.createNativeQuery("SELECT count(*) FROM services").getSingleResult();
        });
        assertThat(services.toString()).as("só o da Ana, nunca o da Eve").isEqualTo("1");
        Object none = tx.execute(s -> em.createNativeQuery("SELECT CAST(tenant_by_slug('nao-existe') AS text)").getSingleResult());
        assertThat(none).isNull();
    }
}
