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
    static final String OWNERS_ONLY = "Only owners can do this";

    private final MembershipRepository memberships;
    private final DbSession db;

    @Transactional(propagation = Propagation.MANDATORY)
    public Membership member(UUID tenantId) {
        Membership m = memberships.findByIdTenantIdAndIdUserId(tenantId, CurrentUser.get().getId())
                .orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        db.enterTenant(tenantId);
        return m;
    }

    /**
     * Como {@link #member}, e só o dono passa (403 para o resto da equipe). Escritas do estabelecimento ficam em fila
     * até o fim da transação (ver {@link DbSession#lockTenant}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void owner(UUID tenantId) {
        if (member(tenantId).getRole() != Role.OWNER) throw ApiException.forbidden(OWNERS_ONLY);
        db.lockTenant(tenantId);
    }

    /**
     * A trava de escrita do estabelecimento, para escritas que a equipe também faz: chame DEPOIS de {@link #member} e da
     * autorização da regra própria — quem vai receber 403 não entra na fila das escritas dos outros.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID tenantId) {
        db.lockTenant(tenantId);
    }
}
