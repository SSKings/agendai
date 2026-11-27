package com.sskings.agendai.notification;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O outbox no banco: RLS por estabelecimento e o aviso preso à transação de quem o causou. */
class OutboxRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @Autowired Outbox outbox;
    @PersistenceContext EntityManager em;

    long rows(String tenant) throws Exception {
        return ownerCount("SELECT count(*) FROM outbox_events WHERE tenant_id = ?::uuid", tenant);
    }

    @Test
    @DisplayName("RLS: dentro de um estabelecimento não grava para outro nem vê os eventos dele; sem estabelecimento, nada")
    void isolation() throws Exception {
        UUID ana = UUID.fromString(register("Ana", email(), "Loja Outbox A").tenantId());
        UUID eve = UUID.fromString(register("Eve", email(), "Loja Outbox B").tenantId());
        UUID id = tx.execute(s -> {
            db.enterTenant(eve);
            return outbox.email(eve, "cliente@exemplo.com", "Assunto", "Texto");
        });
        assertThat(ownerCount("SELECT count(*) FROM outbox_events WHERE id = ?::uuid", id.toString()))
                .as("devolve o id do evento gravado (é o id da mensagem)").isEqualTo(1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            db.enterTenant(ana);
            outbox.email(eve, "x@exemplo.com", "Assunto", "Texto");
        })).hasMessageContaining("row-level security");
        Object seen = tx.execute(s -> {
            db.enterTenant(ana);
            return em.createNativeQuery("SELECT count(*) FROM outbox_events").getSingleResult();
        });
        assertThat(seen.toString()).as("a Ana não vê o evento da Eve").isEqualTo("0");
        Object none = tx.execute(s -> em.createNativeQuery("SELECT count(*) FROM outbox_events").getSingleResult());
        assertThat(none.toString()).isEqualTo("0");
        assertThat(rows(eve.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("Transação desfeita leva o aviso junto; fora de transação, gravar o aviso é recusado")
    void boundToTransaction() throws Exception {
        UUID ana = UUID.fromString(register("Ana", email(), "Loja Outbox C").tenantId());
        tx.executeWithoutResult(s -> {
            db.enterTenant(ana);
            outbox.email(ana, "cliente@exemplo.com", "Assunto", "Texto");
            s.setRollbackOnly();
        });
        assertThat(rows(ana.toString())).isZero();
        assertThatThrownBy(() -> outbox.email(ana, "cliente@exemplo.com", "Assunto", "Texto"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
