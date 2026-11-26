package com.sskings.agendai.db;

import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O aviso de escrita (ADR-012): depois do commit, uma vez por estabelecimento, e só se a transação escreveu. */
@RecordApplicationEvents
class DbSessionTest extends IntegrationTest {

    @Autowired DbSession db;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired ApplicationEvents events;

    /** Escreve de verdade (o PostgreSQL dá um xid à transação). */
    void write() {
        em.createNativeQuery("SELECT txid_current()").getSingleResult();
    }

    long changed() {
        return events.stream(TenantDataChanged.class).count();
    }

    @Test
    @DisplayName("Dois estabelecimentos e entradas repetidas na mesma transação: um aviso para cada, depois do commit")
    void oncePerTenant() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            // outra sincronização na transação (de outro componente) não atrapalha
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {});
            db.enterTenant(a);
            db.enterTenant(a);
            db.enterTenant(b);
            write();
            assertThat(changed()).as("antes do commit").isZero();
        });
        assertThat(events.stream(TenantDataChanged.class).map(TenantDataChanged::tenantId)).containsExactly(a, b);
    }

    @Test
    @DisplayName("Sem escrita, desfeita ou só leitura: nenhum aviso")
    void noWriteNoNotice() {
        tx.executeWithoutResult(s -> db.enterTenant(UUID.randomUUID()));
        tx.executeWithoutResult(s -> {
            db.enterTenant(UUID.randomUUID());
            write();
            s.setRollbackOnly();
        });
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(s -> db.enterTenant(UUID.randomUUID()));
        assertThat(changed()).isZero();
    }
}
