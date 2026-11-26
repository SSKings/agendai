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
import com.sskings.agendai.tenant.TenantService;
import com.sskings.agendai.publicbooking.PublicDtos.RescheduleRequest;
import com.sskings.agendai.publicbooking.PublicDtos.TokenRequest;

import java.time.Instant;
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
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class PublicBookingService {

    private final PublicAccess access;
    private final CatalogService catalog;
    private final ScheduleService schedule;
    private final BookingService bookings;
    private final Holds holds;
    private final Mailer mailer;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final TenantService tenants;
    private final java.time.Clock clock;
    private final SlotCache cache;
    @org.springframework.beans.factory.annotation.Value("${agendai.public-url}")
    private String publicUrl;

    /** Nome, fuso e o que o cliente precisa da política: quantos dias a agenda abre e o prazo para mudar. */
    public record BusinessView(String name, String timeZone, int bookingWindowDays, int cancelUntilHours) {}

    @Transactional(readOnly = true)
    public BusinessView business(String slug) {
        PublicBusiness b = access.enter(slug);
        TenantService.Policy p = tenants.policyOf(b.id());
        return new BusinessView(b.name(), b.timeZone(), p.bookingWindowDays(), p.cancelUntilHours());
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
        // sem cache: a reserva olha o banco agora
        AvailabilityView free = freeSlots(tenantId, r.professionalId(), r.serviceId(), r.start().toLocalDate(), false);
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
            requireNotice(business.id(), h.start());
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

    /** Aviso por e-mail a mandar depois da transação (null: nenhum). */
    private record Notice(String to, String subject, String text) {}

    /** Resultado de uma transação: a resposta e o aviso, ou o erro (lançado só depois do commit). */
    private record Outcome(BookingService.PublicBookingView view, Notice notice, ApiException error) {}

    /**
     * Código certo → CONFIRMED, e o e-mail com o link pessoal (cancelar/remarcar). A tentativa errada também é gravada:
     * a transação devolve o erro em vez de lançá-lo (lançado depois do commit).
     */
    public BookingService.PublicBookingView confirm(String slug, ConfirmRequest r) {
        return finish(tx.execute(s -> {
            try {
                PublicBusiness business = access.enter(slug);
                BookingService.ClientBooking c = bookings.confirm(business.id(), r.token(), r.code());
                // email null: já estava confirmado (clique duplo) — o aviso saiu da primeira vez
                Notice n = c.email() == null ? null : new Notice(c.email(), "Agendamento confirmado — " + business.name(), """
                        Seu agendamento em %s está confirmado: %s com %s, %s.

                        Para cancelar ou remarcar (até %d h antes), use o seu link pessoal — não o compartilhe:
                        %s
                        """.formatted(business.name(), c.view().serviceName(), c.view().professionalName(), WHEN.format(c.view().start()),
                        tenants.policyOf(business.id()).cancelUntilHours(), manageLink(slug, r.token())));
                return new Outcome(c.view(), n, null);
            } catch (ApiException e) {
                return new Outcome(null, null, e);
            }
        }));
    }

    // ---------- link pessoal do cliente (2c) ----------

    @Transactional(readOnly = true)
    public BookingService.ManagedView lookup(String slug, TokenRequest r) {
        UUID tenantId = access.enter(slug).id();
        return bookings.lookup(tenantId, r.token(), tenants.policyOf(tenantId).cancelUntil());
    }

    public BookingService.PublicBookingView cancel(String slug, TokenRequest r) {
        return finish(tx.execute(s -> {
            PublicBusiness business = access.enter(slug);
            BookingService.ClientBooking c = bookings.cancelByClient(business.id(), r.token(), tenants.policyOf(business.id()).cancelUntil());
            return new Outcome(c.view(), new Notice(c.email(), "Agendamento cancelado — " + business.name(), """
                    Seu agendamento em %s foi cancelado: %s com %s, %s.
                    Se não foi você, fale com o estabelecimento.
                    """.formatted(business.name(), c.view().serviceName(), c.view().professionalName(), WHEN.format(c.view().start()))), null);
        }));
    }

    /** Remarca para um horário já reservado (POST /holds antes, com as mesmas regras de sempre). */
    public BookingService.PublicBookingView reschedule(String slug, RescheduleRequest r) {
        return finish(tx.execute(s -> {
            PublicBusiness business = access.enter(slug);
            Holds.Hold h = holds.take(r.holdToken());
            if (h == null || !h.tenantId().equals(business.id())) throw ApiException.badRequest(HOLD_EXPIRED);
            requireNotice(business.id(), h.start());
            BookingService.ClientBooking c = bookings.rescheduleByClient(business.id(), r.token(),
                    tenants.policyOf(business.id()).cancelUntil(), h.professionalId(), h.serviceId(), h.start());
            return new Outcome(c.view(), new Notice(c.email(), "Agendamento remarcado — " + business.name(), """
                    Seu agendamento em %s foi remarcado: %s com %s, %s.

                    O seu link pessoal continua o mesmo:
                    %s
                    """.formatted(business.name(), c.view().serviceName(), c.view().professionalName(), WHEN.format(c.view().start()),
                    manageLink(slug, r.token()))), null);
        }));
    }

    /**
     * A reserva dura 5 min: na hora de usá-la, a antecedência mínima é conferida de novo (o horário pode ter entrado
     * nela enquanto o cliente preenchia).
     */
    private void requireNotice(UUID tenantId, java.time.LocalDateTime start) {
        Instant at = start.atZone(tenants.zoneOf(tenantId)).toInstant();
        if (at.isBefore(clock.instant().plus(tenants.policyOf(tenantId).minNotice()))) throw ApiException.conflict(Holds.TAKEN);
    }

    /** O token vai no fragmento (#): o navegador não o manda ao servidor nem o põe no Referer. */
    private String manageLink(String slug, String token) {
        return publicUrl.replaceAll("/+$", "") + "/agendar/" + slug + "/gerenciar#" + token;
    }

    /**
     * Depois do commit (a transação já devolveu a conexão): o erro guardado sobe; senão o aviso sai — e falhar aqui não
     * desfaz nada (o cliente já viu o resultado; é "melhor esforço" até o outbox da fase 3, emenda ao ADR-003).
     */
    private BookingService.PublicBookingView finish(Outcome o) {
        if (o.error() != null) throw o.error();
        if (o.notice() != null) {
            try {
                mailer.send(o.notice().to(), o.notice().subject(), o.notice().text());
            } catch (RuntimeException e) {
                log.warn("Aviso por e-mail não enviado ({})", o.notice().subject().substring(0, o.notice().subject().indexOf(" — ")));
            }
        }
        return o.view();
    }

    private static final java.time.format.DateTimeFormatter WHEN =
            java.time.format.DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", new java.util.Locale("pt", "BR"));

    @Transactional(readOnly = true)
    public AvailabilityView availability(String slug, UUID professionalId, UUID serviceId, LocalDate date) {
        return freeSlots(access.enter(slug).id(), professionalId, serviceId, date, true);
    }

    /**
     * Horários livres para o cliente: as regras do painel mais a política do estabelecimento — só até
     * {@code bookingWindowDays} à frente e a partir de agora + antecedência mínima.
     */
    private AvailabilityView freeSlots(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date, boolean cached) {
        // na página pública, tudo que não está "ativo e fazendo o serviço" é o mesmo 404: inativo ou sem vínculo não
        // se distingue de inexistente (o painel responde diferente, para o dono)
        if (catalog.activeProfessionalsFor(tenantId, serviceId).stream().noneMatch(p -> p.id().equals(professionalId))) {
            throw ApiException.notFound("Professional not found");
        }
        TenantService.Policy policy = tenants.policyOf(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(tenants.zoneOf(tenantId)));
        if (date.isAfter(today.plusDays(policy.bookingWindowDays()))) throw ApiException.badRequest("Date out of range");
        // o cache guarda as regras do painel (o pesado); a política e o "a partir de agora" são aplicados por cima, sempre
        AvailabilityView all = cached
                ? cache.get(tenantId, professionalId, serviceId, date, () -> schedule.slots(tenantId, professionalId, serviceId, date))
                : schedule.slots(tenantId, professionalId, serviceId, date);
        Instant earliest = clock.instant().plus(policy.minNotice());
        return new AvailabilityView(all.date(), all.timeZone(), all.slots().stream().filter(s -> !s.toInstant().isBefore(earliest)).toList());
    }
}
