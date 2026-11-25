package com.sskings.agendai.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Variáveis da transação lidas pelo Row-Level Security (ADR-006): {@code app.user_id} e {@code app.tenant_id}.
 * {@code set_config(..., true)} vale só até o fim da transação: a conexão volta ao pool sem nada.
 */
@Component
public class DbSession {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public void actAs(UUID userId) {
        set("app.user_id", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enterTenant(UUID tenantId) {
        set("app.tenant_id", tenantId);
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
