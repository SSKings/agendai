package com.sskings.agendai.publicbooking;

import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.catalog.CatalogService.PublicProfessional;
import com.sskings.agendai.catalog.CatalogService.PublicService;
import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import com.sskings.agendai.schedule.ScheduleService;
import com.sskings.agendai.tenant.PublicAccess;
import com.sskings.agendai.tenant.PublicAccess.PublicBusiness;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Página pública do estabelecimento (sem login): serviço → profissional → dia → horário. Só leitura nesta etapa; a
 * reserva e a confirmação por código vêm na 2b. Tudo passa pelo guard público (link → estabelecimento no RLS).
 */
@Component
@RequiredArgsConstructor
public class PublicBookingService {

    private final PublicAccess access;
    private final CatalogService catalog;
    private final ScheduleService schedule;

    public record BusinessView(String name, String timeZone) {}

    @Transactional(readOnly = true)
    public BusinessView business(String slug) {
        PublicBusiness b = access.enter(slug);
        return new BusinessView(b.name(), b.timeZone());
    }

    @Transactional(readOnly = true)
    public List<PublicService> services(String slug) {
        return catalog.activeServices(access.enter(slug).id());
    }

    @Transactional(readOnly = true)
    public List<PublicProfessional> professionals(String slug, UUID serviceId) {
        return catalog.activeProfessionalsFor(access.enter(slug).id(), serviceId);
    }

    @Transactional(readOnly = true)
    public AvailabilityView availability(String slug, UUID professionalId, UUID serviceId, LocalDate date) {
        UUID tenantId = access.enter(slug).id();
        // na página pública, tudo que não está "ativo e fazendo o serviço" é o mesmo 404: inativo ou sem vínculo não
        // se distingue de inexistente (o painel responde diferente, para o dono)
        if (catalog.activeProfessionalsFor(tenantId, serviceId).stream().noneMatch(p -> p.id().equals(professionalId))) {
            throw ApiException.notFound("Professional not found");
        }
        return schedule.slots(tenantId, professionalId, serviceId, date);
    }
}
