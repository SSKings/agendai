package com.sskings.agendai.publicbooking;

import com.sskings.agendai.booking.BookingDtos;
import com.sskings.agendai.booking.BookingService;
import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.notification.Mailer;
import com.sskings.agendai.publicbooking.PublicDtos.BookRequest;
import com.sskings.agendai.publicbooking.PublicDtos.ClientInput;
import com.sskings.agendai.publicbooking.PublicDtos.ConfirmRequest;
import com.sskings.agendai.publicbooking.PublicDtos.HoldRequest;
import com.sskings.agendai.publicbooking.PublicDtos.HoldView;
import com.sskings.agendai.publicbooking.PublicDtos.PendingView;
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
 * Página pública do estabelecimento (sem login): serviço → profissional → dia → horário → reserva de 5 min → dados →
 * código por e-mail → confirmado. Tudo passa pelo guard público (link → estabelecimento no RLS, ADR-011).
 */
@Component
@RequiredArgsConstructor
public class PublicBookingService {

    private final PublicAccess access;
    private final CatalogService catalog;
    private final ScheduleService schedule;
    private final BookingService bookings;
    private final Holds holds;
    private final Mailer mailer;
    private final org.springframework.transaction.support.TransactionTemplate tx;

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

    static final String HOLD_EXPIRED = "Hold expired";

    /** Segura um horário que está livre AGORA (as mesmas regras dos horários livres), por 5 min. */
    @Transactional(readOnly = true)
    public HoldView hold(String slug, HoldRequest r, String ip) {
        UUID tenantId = access.enter(slug).id();
        AvailabilityView free = freeSlots(tenantId, r.professionalId(), r.serviceId(), r.start().toLocalDate());
        boolean listed = free.slots().stream().anyMatch(s -> s.toLocalDateTime().equals(r.start()));
        if (!listed) throw ApiException.conflict(Holds.TAKEN);
        String token = holds.create(new Holds.Hold(tenantId, r.professionalId(), r.serviceId(), r.start()), ip);
        return new HoldView(token, holds.expiresAt());
    }

    /**
     * Com a reserva e os dados do cliente: agendamento PENDING (transação própria, já gravada) e só então o código por
     * e-mail — fora da transação, para um provedor lento não segurar conexão nem trava do banco. Se o e-mail não sai, o
     * pendente é expirado numa nova transação (não fica segurando o horário) e a resposta é 503.
     */
    public PendingView book(String slug, BookRequest r) {
        ClientInput c = r.customer();
        Created created = tx.execute(s -> {
            PublicBusiness business = access.enter(slug);
            Holds.Hold h = holds.take(r.holdToken());
            if (h == null || !h.tenantId().equals(business.id())) throw ApiException.badRequest(HOLD_EXPIRED);
            return new Created(business, bookings.createPending(business.id(), h.professionalId(), h.serviceId(), h.start(),
                    new BookingDtos.CustomerInput(c.name(), c.phone(), c.email())));
        });
        BookingService.PendingBooking p = created.pending();
        try {
            // sem o nome digitado no texto: o e-mail vai para um endereço qualquer, com o remetente do Agendaí
            mailer.send(c.email(), "Seu código: " + p.code() + " — " + created.business().name(), """
                    Para confirmar o agendamento em %s, use o código:

                        %s

                    %s com %s, %s.
                    O código vale por 10 minutos. Se não foi você que pediu, ignore este e-mail: sem o código, nada é confirmado.
                    """.formatted(created.business().name(), p.code(), p.serviceName(), p.professionalName(), WHEN.format(p.start())));
        } catch (ApiException e) {
            tx.executeWithoutResult(s -> bookings.abandon(access.enter(slug).id(), p.id()));
            throw e;
        }
        return new PendingView(p.token(), p.pendingUntil());
    }

    private record Created(PublicBusiness business, BookingService.PendingBooking pending) {}

    /** Código certo → CONFIRMED. As tentativas erradas ficam contadas mesmo com o erro (não desfaz a transação). */
    @Transactional(noRollbackFor = ApiException.class)
    public BookingService.PublicBookingView confirm(String slug, ConfirmRequest r) {
        return bookings.confirm(access.enter(slug).id(), r.token(), r.code());
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", new java.util.Locale("pt", "BR"));

    @Transactional(readOnly = true)
    public AvailabilityView availability(String slug, UUID professionalId, UUID serviceId, LocalDate date) {
        return freeSlots(access.enter(slug).id(), professionalId, serviceId, date);
    }

    private AvailabilityView freeSlots(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date) {
        // na página pública, tudo que não está "ativo e fazendo o serviço" é o mesmo 404: inativo ou sem vínculo não
        // se distingue de inexistente (o painel responde diferente, para o dono)
        if (catalog.activeProfessionalsFor(tenantId, serviceId).stream().noneMatch(p -> p.id().equals(professionalId))) {
            throw ApiException.notFound("Professional not found");
        }
        return schedule.slots(tenantId, professionalId, serviceId, date);
    }
}
