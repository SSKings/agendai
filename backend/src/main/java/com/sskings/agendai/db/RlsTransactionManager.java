package com.sskings.agendai.db;

import com.sskings.agendai.security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Toda transação começa com o usuário logado em {@code app.user_id}: o RLS do banco só mostra a ele as participações e
 * os estabelecimentos dele, mesmo que uma consulta esqueça de filtrar (ADR-006).
 */
public class RlsTransactionManager extends JpaTransactionManager {

    public RlsTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        CurrentUser.id().ifPresent(id -> EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory())
                .createNativeQuery("SELECT set_config('app.user_id', :id, true)").setParameter("id", id.toString())
                .getSingleResult());
    }
}
