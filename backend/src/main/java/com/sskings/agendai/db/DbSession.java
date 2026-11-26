package com.sskings.agendai.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Variáveis da transação lidas pelo Row-Level Security (ADR-006): {@code app.user_id} e {@code app.tenant_id}.
 * {@code set_config(..., true)} vale só até o fim da transação: a conexão volta ao pool sem nada.
 */
@Component
@RequiredArgsConstructor
public class DbSession {

    @PersistenceContext
    private EntityManager em;

    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    public void actAs(UUID userId) {
        set("app.user_id", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enterTenant(UUID tenantId) {
        set("app.tenant_id", tenantId);
        // toda escrita de um estabelecimento passa por aqui (é o RLS que a deixa ver as linhas): depois do commit, avisa
        // — uma vez por transação e estabelecimento, e só se a transação escreveu de fato. Leitura não avisa.
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return;
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (s instanceof Changed c) {
                c.tenants.add(tenantId);
                return;
            }
        }
        TransactionSynchronizationManager.registerSynchronization(new Changed(tenantId));
    }

    /**
     * Sincronização da transação corrente (o Spring a suspende junto com a transação, em REQUIRES_NEW): guarda os
     * estabelecimentos e, antes do commit, se houve escrita — o PostgreSQL só dá um xid a quem escreveu (set_config e a
     * trava consultiva não contam). Uma tentativa anônima que não grava nada não renova o cache de ninguém.
     */
    private final class Changed implements TransactionSynchronization {
        private final java.util.Set<UUID> tenants = new java.util.LinkedHashSet<>();
        private boolean wrote;

        Changed(UUID tenantId) {
            tenants.add(tenantId);
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            em.flush();   // o que o Hibernate ainda não mandou ao banco conta como escrita
            wrote = (Boolean) em.createNativeQuery("SELECT txid_current_if_assigned() IS NOT NULL").getSingleResult();
        }

        @Override
        public void afterCommit() {
            if (wrote) tenants.forEach(t -> events.publishEvent(new TenantDataChanged(t)));
        }
    }

    /**
     * Serializa as escritas de um estabelecimento até o fim da transação (trava consultiva do PostgreSQL): regras de
     * "conta e depois grava" (teto de cadastros, troca da lista de vínculos) não furam com requisições simultâneas.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenant(UUID tenantId) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:id, 0))")
                .setParameter("id", tenantId.toString()).getSingleResult();
    }

    private void set(String name, UUID value) {
        em.createNativeQuery("SELECT set_config(:name, :value, true)")
                .setParameter("name", name).setParameter("value", value.toString()).getSingleResult();
    }
}
