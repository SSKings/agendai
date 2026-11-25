package com.sskings.agendai.catalog;

import com.sskings.agendai.common.Tokens;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Convites no banco, sem a API por cima: RLS da tabela e a função redeem_invite (SECURITY DEFINER). */
class InviteRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("Convites da Ana invisíveis no estabelecimento da Eve; inserir com o tenant_id da Ana é barrado")
    void rls() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String joao = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals"), ana.token(),
                Map.of("name", "João", "active", true, "serviceIds", List.of()))).get("id").asString();
        call(post("/api/tenants/" + ana.tenantId() + "/professionals/" + joao + "/invite"), ana.token(), null);
        Object count = tx.execute(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            db.enterTenant(UUID.fromString(eve.tenantId()));
            return em.createNativeQuery("SELECT count(*) FROM invites").getSingleResult();
        });
        assertThat(count.toString()).isEqualTo("0");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            db.enterTenant(UUID.fromString(eve.tenantId()));
            em.createNativeQuery("""
                    INSERT INTO invites (id, tenant_id, professional_id, token_hash, created_by, created_at, expires_at)
                    VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), 'x', cast(:u as uuid), now(), now() + interval '1 hour')""")
                    .setParameter("t", ana.tenantId()).setParameter("p", joao).setParameter("u", eve.userId()).executeUpdate();
        })).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("redeem_invite sem usuário na transação não faz nada; o RLS de memberships continua barrando entrada direta")
    void redeemNeedsUser() throws Exception {
        Session ana = register();
        String joao = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals"), ana.token(),
                Map.of("name", "João", "active", true, "serviceIds", List.of()))).get("id").asString();
        String code = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals/" + joao + "/invite"), ana.token(), null))
                .get("code").asString();
        Object[] row = tx.execute(s -> (Object[]) em.createNativeQuery("SELECT CAST(tenant AS text), outcome FROM redeem_invite(:h, now())")
                .setParameter("h", Tokens.sha256(code)).getSingleResult());
        assertThat(row[0]).isNull();
        assertThat(row[1]).isEqualTo("INVALID");
        Session eve = register("Eve", email(), "Loja da Eve");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            em.createNativeQuery("INSERT INTO memberships (tenant_id, user_id, role, joined_at) VALUES (cast(:t as uuid), cast(:u as uuid), 'PROFESSIONAL', now())")
                    .setParameter("t", ana.tenantId()).setParameter("u", eve.userId()).executeUpdate();
        })).as("sem a função, ninguém entra em estabelecimento alheio").hasMessageContaining("row-level security");
        assertThat(ownerCount("SELECT count(*) FROM invites WHERE used_at IS NULL")).isPositive();
    }
}
