package com.sskings.agendai.tenant;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Guard de acesso (1ª camada do ADR-006): toda rota de estabelecimento passa por aqui, com o usuário do token.
 * Não participa → 404 (não revela que o estabelecimento existe); participa → a transação passa a valer para aquele
 * estabelecimento no RLS (3ª camada).
 */
@Component
@RequiredArgsConstructor
public class TenantAccess {

    static final String NOT_FOUND = "Tenant not found";

    private final MembershipRepository memberships;
    private final DbSession db;

    @Transactional(propagation = Propagation.MANDATORY)
    public Membership member(UUID tenantId) {
        Membership m = memberships.findByIdTenantIdAndIdUserId(tenantId, CurrentUser.get().getId())
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        db.enterTenant(tenantId);
        return m;
    }
}
