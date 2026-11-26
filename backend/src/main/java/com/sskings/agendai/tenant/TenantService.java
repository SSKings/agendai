package com.sskings.agendai.tenant;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sskings.agendai.common.ApiException;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantService {

    static final String DEFAULT_ZONE = "America/Sao_Paulo";

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final TenantAccess access;
    private final DbSession db;
    private final Clock clock;

    public record TenantView(UUID id, String name, String slug, String timeZone, Role role) {}

    /** Política do estabelecimento para o cliente (a página pública e o link pessoal seguem; o painel, não). */
    public record Policy(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(10080)
            Integer minNoticeMinutes,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(720)
            Integer cancelUntilHours,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(180)
            Integer bookingWindowDays) {

        public java.time.Duration minNotice() {
            return java.time.Duration.ofMinutes(minNoticeMinutes);
        }

        public java.time.Duration cancelUntil() {
            return java.time.Duration.ofHours(cancelUntilHours);
        }
    }

    static final String FIVE_MINUTE_STEPS = "Times must be multiples of 5 minutes";

    @Transactional(readOnly = true)
    public Policy policy(UUID tenantId) {
        access.member(tenantId);
        return policyOf(tenantId);
    }

    @Transactional
    public Policy updatePolicy(UUID tenantId, Policy p) {
        access.owner(tenantId);
        if (p.minNoticeMinutes() % 5 != 0) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
        tenants.findById(tenantId).orElseThrow().changePolicy(p.minNoticeMinutes(), p.cancelUntilHours(), p.bookingWindowDays());
        return p;
    }

    /** Dentro de uma transação que já passou por um guard (painel ou página pública). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Policy policyOf(UUID tenantId) {
        Tenant t = tenants.findById(tenantId).orElseThrow();   // depois do guard, o estabelecimento existe e é visível
        return new Policy(t.getMinNoticeMinutes(), t.getCancelUntilHours(), t.getBookingWindowDays());
    }

    /** No cadastro: o dono cria o estabelecimento dele. Link único entre todos ("-2", "-3"... se o nome já existir). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Tenant createFor(User owner, String name) {
        db.actAs(owner.getId());                      // o RLS aceita a participação do próprio dono
        String base = Slugs.of(name);
        String slug = base;
        for (int n = 2; tenants.slugTaken(slug); n++) slug = base + "-" + n;
        Tenant t = tenants.save(new Tenant(name, slug, DEFAULT_ZONE, clock.instant()));
        memberships.save(new Membership(t.getId(), owner.getId(), Role.OWNER, clock.instant()));
        return t;
    }

    @Transactional(readOnly = true)
    public TenantView get(UUID id) {
        Membership m = access.member(id);
        Tenant t = tenants.findById(id).orElseThrow();
        return new TenantView(t.getId(), t.getName(), t.getSlug(), t.getTimeZone(), m.getRole());
    }

    /**
     * Fuso do estabelecimento, dentro de uma transação que já passou pelo guard. Não substitui o guard: o RLS de
     * {@code tenants} mostra todos os estabelecimentos de que o usuário participa.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ZoneId zoneOf(UUID tenantId) {
        return ZoneId.of(tenants.timeZoneOf(tenantId).orElseThrow(() -> ApiException.notFound(TenantAccess.NOT_FOUND)));
    }

    /** Tira um profissional da equipe (nunca o dono), dentro de uma transação que já passou pelo guard do dono. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeProfessional(UUID tenantId, UUID userId) {
        memberships.findById(new Membership.Key(tenantId, userId))
                .filter(m -> m.getRole() == Role.PROFESSIONAL)
                .ifPresent(memberships::delete);
        memberships.flush();   // a FK de professionals.user_id (ON DELETE SET NULL) age já, dentro da transação
    }

    /** Os estabelecimentos de quem está logado (o RLS já restringe às participações dele). */
    @Transactional(readOnly = true)
    public List<TenantView> mine(UUID userId) {
        return memberships.viewsOf(userId);
    }
}
