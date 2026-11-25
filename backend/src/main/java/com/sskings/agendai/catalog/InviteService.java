package com.sskings.agendai.catalog;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.security.CurrentUser;
import com.sskings.agendai.tenant.TenantAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Convite para a equipe (docs/seguranca.md): código aleatório de 256 bits mostrado uma vez; no banco, só o SHA-256;
 * uso único; expira em 72 h; um convite novo ou o cancelamento revogam o anterior. Aceitar passa pela função do banco
 * (redeem_invite), porque quem aceita ainda não participa do estabelecimento e o RLS não deixaria.
 */
@Component
@RequiredArgsConstructor
public class InviteService {

    static final Duration VALIDITY = Duration.ofHours(72);
    static final String HAS_ACCOUNT = "Professional already has an account";
    static final String INVALID = "Invalid or expired invite";
    static final String ALREADY_MEMBER = "Already a member of this business";
    static final String INACTIVE = "Professional is inactive";
    static final String NO_ACCOUNT = "Professional has no linked account";

    public record InviteView(String code, Instant expiresAt) {}

    public record AcceptRequest(String code) {}

    public record AcceptedView(UUID tenantId) {}

    private final TenantAccess access;
    private final ProfessionalRepository professionals;
    private final InviteRepository invites;
    private final com.sskings.agendai.tenant.TenantService tenants;
    private final Clock clock;

    @Transactional
    public InviteView create(UUID tenantId, UUID professionalId) {
        access.owner(tenantId);
        Professional p = professionals.findByIdAndTenantId(professionalId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Professional not found"));
        if (p.getUserId() != null) throw ApiException.conflict(HAS_ACCOUNT);
        if (!p.isActive()) throw ApiException.conflict(INACTIVE);
        Instant now = clock.instant();
        invites.revokeActive(tenantId, professionalId, now);
        String code = Tokens.random();
        Instant expiresAt = now.plus(VALIDITY);
        invites.save(new Invite(tenantId, professionalId, Tokens.sha256(code), CurrentUser.get().getId(), now, expiresAt));
        return new InviteView(code, expiresAt);
    }

    @Transactional
    public void revoke(UUID tenantId, UUID professionalId) {
        access.owner(tenantId);
        professionals.findByIdAndTenantId(professionalId, tenantId).orElseThrow(() -> ApiException.notFound("Professional not found"));
        invites.revokeActive(tenantId, professionalId, clock.instant());
    }

    /**
     * Tira a conta do profissional da equipe (saiu do estabelecimento): apaga a participação dela — a FK composta de
     * professionals.user_id desfaz o vínculo sozinha (V5) — e revoga convites pendentes. Os agendamentos ficam.
     */
    @Transactional
    public void removeAccount(UUID tenantId, UUID professionalId) {
        access.owner(tenantId);
        Professional p = professionals.findByIdAndTenantId(professionalId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Professional not found"));
        if (p.getUserId() == null) throw ApiException.notFound(NO_ACCOUNT);
        tenants.removeProfessional(tenantId, p.getUserId());
        invites.revokeActive(tenantId, professionalId, clock.instant());
    }

    /** Qualquer conta logada aceita. Código malformado, desconhecido, usado, revogado ou vencido: a mesma resposta. */
    @Transactional
    public AcceptedView accept(AcceptRequest r) {
        if (!Tokens.wellFormed(r.code())) throw ApiException.badRequest(INVALID);
        List<Object[]> result = invites.redeem(Tokens.sha256(r.code()), clock.instant());
        Object[] row = result.get(0);
        return switch ((String) row[1]) {
            case "OK" -> new AcceptedView(UUID.fromString((String) row[0]));
            case "MEMBER" -> throw ApiException.conflict(ALREADY_MEMBER);
            default -> throw ApiException.badRequest(INVALID);
        };
    }
}
