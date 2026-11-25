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

    private void set(String name, UUID value) {
        em.createNativeQuery("SELECT set_config(:name, :value, true)")
                .setParameter("name", name).setParameter("value", value.toString()).getSingleResult();
    }
}
