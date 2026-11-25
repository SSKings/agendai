package com.sskings.agendai.tenant;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.user.User;
import com.sskings.agendai.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O guard (1ª camada) liga a 3ª: depois dele, a transação vale só para aquele estabelecimento no RLS. */
class TenantAccessTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired TenantAccess access;
    @Autowired UserRepository users;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private String currentTenant() {
        return (String) em.createNativeQuery("SELECT coalesce(current_setting('app.tenant_id', true), '')").getSingleResult();
    }

    private void loginAs(UUID userId) {
        User u = tx.execute(s -> {
            db.actAs(userId);
            return users.findById(userId).orElseThrow();
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null));
    }

    @Test
    @DisplayName("Membro: o guard devolve o vínculo e fixa o estabelecimento na transação; não membro: 404 e nada fixado")
    void guardEntersTenant() throws Exception {
        Session ana = register();
        Session bia = register("Bia", email(), "Clínica da Bia");
        UUID anaTenant = UUID.fromString(ana.tenantId());
        loginAs(UUID.fromString(ana.userId()));
        tx.executeWithoutResult(s -> {
            assertThat(currentTenant()).isEmpty();
            assertThat(access.member(anaTenant).getTenantId()).isEqualTo(anaTenant);
            assertThat(currentTenant()).isEqualTo(anaTenant.toString());
        });
        tx.executeWithoutResult(s -> {
            assertThatThrownBy(() -> access.member(UUID.fromString(bia.tenantId())))
                    .isInstanceOf(ApiException.class).hasMessage(TenantAccess.NOT_FOUND);
            assertThat(currentTenant()).isEmpty();
            s.setRollbackOnly();                                // o 404 desfaz a transação, como na rota
        });
    }
}
