package com.sskings.agendai.catalog;

import com.sskings.agendai.catalog.CatalogDtos.ProfessionalRequest;
import com.sskings.agendai.catalog.CatalogDtos.ProfessionalView;
import com.sskings.agendai.catalog.CatalogDtos.ServiceRequest;
import com.sskings.agendai.catalog.CatalogDtos.ServiceView;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.security.CurrentUser;
import com.sskings.agendai.tenant.Membership;
import com.sskings.agendai.tenant.Role;
import com.sskings.agendai.tenant.TenantAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serviços e profissionais do estabelecimento. Toda operação passa pelo guard (membro lê; só o dono altera), e as
 * listas têm teto por estabelecimento: nenhuma rota devolve lista sem limite.
 */
@Component
@RequiredArgsConstructor
public class CatalogService {

    /** Limites de um serviço (também CHECK em services, V2); a agenda deriva deles a duração máxima de um agendamento. */
    public static final int MAX_DURATION = 480;
    public static final int MAX_BUFFER = 120;

    static final int MAX_SERVICES = 100;
    static final int MAX_PROFESSIONALS = 50;

    private final TenantAccess access;
    private final ServiceRepository services;
    private final ProfessionalRepository professionals;
    private final ProfessionalServiceRepository links;
    private final Clock clock;

    // ---------- serviços ----------

    @Transactional(readOnly = true)
    public List<ServiceView> services(UUID tenantId) {
        access.member(tenantId);
        return services.listOf(tenantId).stream().map(ServiceView::of).toList();
    }

    @Transactional
    public ServiceView createService(UUID tenantId, ServiceRequest r) {
        access.owner(tenantId);
        checkSteps(r);
        if (services.countByTenantId(tenantId) >= MAX_SERVICES) throw ApiException.conflict("Service limit reached");
        checkServiceName(tenantId, r.name(), null);
        return ServiceView.of(services.save(new Service(tenantId, r, clock.instant())));
    }

    @Transactional
    public ServiceView updateService(UUID tenantId, UUID id, ServiceRequest r) {
        access.owner(tenantId);
        checkSteps(r);
        Service s = services.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Service not found"));
        checkServiceName(tenantId, r.name(), id);
        s.apply(r);
        return ServiceView.of(s);
    }

    /** A agenda trabalha em passos de 5 min (docs/produto.md): duração e intervalo também. */
    private static void checkSteps(ServiceRequest r) {
        if (r.durationMinutes() % 5 != 0 || r.bufferMinutes() % 5 != 0) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
    }

    static final String FIVE_MINUTE_STEPS = "Durations must be multiples of 5 minutes";

    private void checkServiceName(UUID tenantId, String name, UUID except) {
        if (services.nameTaken(tenantId, name, except == null ? NONE : except)) throw ApiException.conflict("Service name already in use");
    }

    // ---------- profissionais ----------

    @Transactional(readOnly = true)
    public List<ProfessionalView> professionals(UUID tenantId) {
        access.member(tenantId);
        Map<UUID, List<UUID>> byProfessional = links.findByTenantId(tenantId).stream().collect(Collectors.groupingBy(
                l -> l.getId().professionalId(), Collectors.mapping(l -> l.getId().serviceId(), Collectors.toList())));
        return professionals.listOf(tenantId).stream()
                .map(p -> view(p, byProfessional.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    @Transactional
    public ProfessionalView createProfessional(UUID tenantId, ProfessionalRequest r) {
        access.owner(tenantId);
        if (professionals.countByTenantId(tenantId) >= MAX_PROFESSIONALS) throw ApiException.conflict("Professional limit reached");
        checkProfessionalName(tenantId, r.name(), null);
        Set<UUID> ids = knownServices(tenantId, r.serviceIds());
        Professional p = professionals.save(new Professional(tenantId, r.name(), r.active(), clock.instant()));
        return view(p, link(tenantId, p.getId(), ids));
    }

    @Transactional
    public ProfessionalView updateProfessional(UUID tenantId, UUID id, ProfessionalRequest r) {
        access.owner(tenantId);
        Professional p = professional(tenantId, id);
        checkProfessionalName(tenantId, r.name(), id);
        Set<UUID> ids = knownServices(tenantId, r.serviceIds());
        p.change(r.name(), r.active());
        links.deleteOf(tenantId, id);                 // DELETE em lote: executa na hora, antes dos novos vínculos
        return view(p, link(tenantId, id, ids));
    }

    private void checkProfessionalName(UUID tenantId, String name, UUID except) {
        if (professionals.nameTaken(tenantId, name, except == null ? NONE : except)) {
            throw ApiException.conflict("Professional name already in use");
        }
    }

    /** Ids repetidos contam uma vez; algum que não seja serviço DESTE estabelecimento → 400. */
    private Set<UUID> knownServices(UUID tenantId, List<UUID> ids) {
        Set<UUID> unique = new LinkedHashSet<>(ids);
        if (services.countByTenantIdAndIdIn(tenantId, unique) != unique.size()) {
            throw ApiException.badRequest("Unknown service");
        }
        return unique;
    }

    private List<UUID> link(UUID tenantId, UUID professionalId, Set<UUID> serviceIds) {
        List<ProfessionalService> saved = new ArrayList<>();
        for (UUID s : serviceIds) saved.add(new ProfessionalService(tenantId, professionalId, s));
        links.saveAll(saved);
        return List.copyOf(serviceIds);
    }

    private static ProfessionalView view(Professional p, List<UUID> serviceIds) {
        return new ProfessionalView(p.getId(), p.getName(), p.isActive(), serviceIds, p.getUserId() != null,
                CurrentUser.id().map(id -> id.equals(p.getUserId())).orElse(false));
    }

    // ---------- página pública (só o que o cliente precisa ver; nada da equipe nem de clientes) ----------

    public record PublicService(UUID id, String name, int durationMinutes, int priceCents) {}

    public record PublicProfessional(UUID id, String name) {}

    /** Serviços ativos, dentro de uma transação que já passou pelo guard público. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PublicService> activeServices(UUID tenantId) {
        return services.listOf(tenantId).stream().filter(Service::isActive)
                .map(s -> new PublicService(s.getId(), s.getName(), s.getDurationMinutes(), s.getPriceCents())).toList();
    }

    /** Quem faz um serviço ativo (404 se o serviço não existe ou está inativo: na página pública, tanto faz). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PublicProfessional> activeProfessionalsFor(UUID tenantId, UUID serviceId) {
        services.findByIdAndTenantId(serviceId, tenantId).filter(Service::isActive)
                .orElseThrow(() -> ApiException.notFound("Service not found"));
        return professionals.activeFor(tenantId, serviceId).stream().map(p -> new PublicProfessional(p.getId(), p.getName())).toList();
    }

    // ---------- para outros módulos (agenda) ----------

    /** O que a agenda precisa de um serviço feito por um profissional; {@code bookable}: os dois estão ativos. */
    public record Offer(int durationMinutes, int bufferMinutes, int priceCents, boolean bookable, String serviceName, String professionalName) {}

    static final String NOT_PERFORMED = "Professional does not perform this service";

    /**
     * Só dentro de uma transação que já passou pelo guard ({@link TenantAccess}): sem o estabelecimento definido, o RLS
     * esconde tudo e a resposta é 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireProfessional(UUID tenantId, UUID professionalId) {
        professional(tenantId, professionalId);
    }

    /** Como {@link #requireProfessional}: 404 para quem não é do estabelecimento; 400 se o profissional não faz o serviço. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Offer offer(UUID tenantId, UUID professionalId, UUID serviceId) {
        Professional p = professional(tenantId, professionalId);
        Service s = services.findByIdAndTenantId(serviceId, tenantId).orElseThrow(() -> ApiException.notFound("Service not found"));
        if (!links.existsByTenantIdAndId(tenantId, new ProfessionalService.Link(professionalId, serviceId))) {
            throw ApiException.badRequest(NOT_PERFORMED);
        }
        return new Offer(s.getDurationMinutes(), s.getBufferMinutes(), s.getPriceCents(), p.isActive() && s.isActive(), s.getName(), p.getName());
    }

    /** Só o profissional vinculado mexe na própria agenda; o dono, em todas. */
    public static final String OWN_AGENDA = "Only your own agenda";

    /**
     * Quem está agindo no estabelecimento: o dono (age por todos) ou um profissional da equipe (só pelo profissional
     * vinculado à conta dele; sem vínculo, por ninguém).
     */
    public record Actor(boolean owner, UUID professionalId) {
        public boolean actsFor(UUID professional) {
            return owner || professional.equals(professionalId);
        }

        /** 403 se não puder agir por este profissional. */
        public void requireFor(UUID professional) {
            if (!actsFor(professional)) throw ApiException.forbidden(OWN_AGENDA);
        }

        /**
         * Para o que pode ser de um profissional ou do estabelecimento inteiro (null, ex.: feriado): o dono age em tudo;
         * o profissional, só no que é dele.
         */
        public boolean actsOn(UUID professionalOrNull) {
            return owner || professionalOrNull != null && actsFor(professionalOrNull);
        }

        /** O profissional da conta (para filtrar "a minha agenda"); 403 se a conta não tiver vínculo. */
        public UUID own() {
            if (professionalId == null) throw ApiException.forbidden(OWN_AGENDA);
            return professionalId;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Actor actor(UUID tenantId, Membership m) {
        return m.getRole() == Role.OWNER ? new Actor(true, null)
                : new Actor(false, professionals.idOfUser(tenantId, m.getId().userId()).orElse(null));
    }

    private Professional professional(UUID tenantId, UUID id) {
        return professionals.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound("Professional not found"));
    }

    /** Sentinela para "nenhum id a ignorar" na checagem de nome repetido (evita um ramo só para null na consulta). */
    private static final UUID NONE = new UUID(0, 0);
}
