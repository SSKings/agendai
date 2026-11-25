package com.sskings.agendai.booking;

import com.sskings.agendai.booking.BookingDtos.AgendaView;
import com.sskings.agendai.booking.BookingDtos.BookingRequest;
import com.sskings.agendai.booking.BookingDtos.BookingView;
import com.sskings.agendai.booking.BookingDtos.CustomerInput;
import com.sskings.agendai.booking.BookingDtos.CustomerView;
import com.sskings.agendai.booking.BookingDtos.StatusRequest;
import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.schedule.ScheduleService;
import com.sskings.agendai.schedule.WorkCalendar;
import com.sskings.agendai.tenant.TenantAccess;
import com.sskings.agendai.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agendamento manual pelo painel e agenda do dia/semana. Qualquer pessoa da equipe agenda e marca atendido/falta/
 * cancelado (quem atende o telefone); a garantia contra horário duplo é do banco (ADR-002), não de uma trava aqui.
 */
@Component
@RequiredArgsConstructor
public class BookingService {

    /** Duração máxima de um agendamento (serviço + preparo; também CHECK no banco, V4): limita "o que ocupa". */
    static final Duration LONGEST = Duration.ofMinutes(CatalogService.MAX_DURATION + CatalogService.MAX_BUFFER);
    static final int MAX_AGENDA_DAYS = 7;
    static final int MAX_AGENDA = 500;
    static final int HISTORY_DAYS = 366;
    /** Agendamentos criados por estabelecimento em 24 h: muito acima de um negócio pequeno, barra criação em massa. */
    static final int MAX_CREATED_PER_DAY = 1000;

    static final String NOT_FOUND = "Booking not found";
    static final String INACTIVE = "Service or professional inactive";
    static final String FIVE_MINUTE_STEPS = "Times must be multiples of 5 minutes";
    static final String INVALID_PHONE = "Invalid phone";
    static final String PAST = "Start in the past";
    static final String DATE_OUT_OF_RANGE = "Date out of range";
    static final String OUTSIDE_HOURS = "Outside working hours";
    static final String TAKEN = "Time slot not available";
    static final String INVALID_PERIOD = "Invalid period";
    static final String INVALID_STATUS = "Invalid status change";
    static final String NOT_STARTED = "Booking has not started";
    static final String DAILY_LIMIT = "Booking limit reached";
    static final String AMBIGUOUS_TIME = "Time does not exist or repeats on this day";
    static final String PHONE_TAKEN = "Phone registered with another name";

    private final TenantAccess access;
    private final TenantService tenants;
    private final CatalogService catalog;
    private final ScheduleService schedule;
    private final BookingRepository bookings;
    private final CustomerRepository customers;
    private final Clock clock;

    @Transactional
    public BookingView create(UUID tenantId, BookingRequest r) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        CatalogService.Offer offer = catalog.offer(tenantId, r.professionalId(), r.serviceId());
        actor.requireFor(r.professionalId());          // profissional da equipe: só na própria agenda
        if (!offer.bookable()) throw ApiException.conflict(INACTIVE);
        LocalDateTime local = r.start();
        if (local.getMinute() % 5 != 0 || local.getSecond() != 0 || local.getNano() != 0) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
        String phone = digits(r.customer().phone());
        ZoneId zone = tenants.zoneOf(tenantId);
        Instant now = clock.instant();
        // na troca de horário de verão um horário local pode não existir (02:30 vira 03:30) ou acontecer duas vezes:
        // nos dois casos o atZone escolheria em silêncio — recusa (ADR-008)
        if (zone.getRules().getValidOffsets(local).size() != 1) throw ApiException.badRequest(AMBIGUOUS_TIME);
        Instant start = local.atZone(zone).toInstant();
        if (start.isBefore(now)) throw ApiException.badRequest(PAST);
        if (local.toLocalDate().isAfter(LocalDate.now(clock.withZone(zone)).plusDays(ScheduleService.HORIZON_DAYS))) {
            throw ApiException.badRequest(DATE_OUT_OF_RANGE);
        }
        Instant end = start.plus(Duration.ofMinutes(offer.durationMinutes()));
        Instant blockedUntil = end.plus(Duration.ofMinutes(offer.bufferMinutes()));
        if (!schedule.calendar(tenantId, zone, List.of(r.professionalId()), start, blockedUntil).covers(r.professionalId(), start, blockedUntil)) {
            throw ApiException.conflict(OUTSIDE_HOURS);
        }
        if (bookings.countByTenantIdAndCreatedAtAfter(tenantId, now.minus(Duration.ofDays(1))) >= MAX_CREATED_PER_DAY) {
            throw ApiException.conflict(DAILY_LIMIT);
        }
        Customer customer = customer(tenantId, r.customer(), phone, now);
        Booking b = new Booking(tenantId, r.professionalId(), r.serviceId(), customer.getId(), start, end, blockedUntil,
                offer.priceCents(), r.note(), now);
        try {
            bookings.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        BookingView view = view(b, customer, zone, false);
        // profissional da equipe: o e-mail de um cliente que já existia não volta para ele (pode ser de outro profissional)
        return actor.owner() ? view : withEmail(view, r.customer().email());
    }

    private static BookingView withEmail(BookingView v, String email) {
        CustomerView c = v.customer();
        return new BookingView(v.id(), v.professionalId(), v.serviceId(), new CustomerView(c.id(), c.name(), c.phone(), email),
                v.start(), v.end(), v.status(), v.priceCents(), v.note(), v.outsideHours());
    }

    /** Violação da restrição de exclusão = alguém agendou antes (409 claro); qualquer outra segue como erro de dados. */
    static RuntimeException translate(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMostSpecificCause().getMessage());
        return detail.contains("bookings_no_overlap") ? ApiException.conflict(TAKEN) : e;
    }

    /** "(11) 98765-4321" → "11987654321": o mesmo telefone digitado de jeitos diferentes é o mesmo cliente. */
    static String digits(String phone) {
        String d = phone.replaceAll("\\D", "");
        if (d.length() < 10 || d.length() > 15) throw ApiException.badRequest(INVALID_PHONE);
        return d;
    }

    /**
     * Telefone já cadastrado com outro nome → 409 sem revelar o nome guardado: um telefone digitado errado não prende o
     * agendamento ao cliente de outra pessoa nem serve de consulta "telefone → nome e e-mail".
     */
    private Customer customer(UUID tenantId, CustomerInput c, String phone, Instant now) {
        customers.insertIfAbsent(UUID.randomUUID(), tenantId, c.name(), phone, c.email(), now);
        Customer found = customers.findByTenantIdAndPhone(tenantId, phone).orElseThrow();
        if (!found.getName().equalsIgnoreCase(c.name())) throw ApiException.conflict(PHONE_TAKEN);
        return found;
    }

    /** Agenda de {@code from} a {@code to} (dias do fuso do estabelecimento, até 7), de todos ou de um profissional. */
    @Transactional(readOnly = true)
    public AgendaView agenda(UUID tenantId, LocalDate from, LocalDate to, UUID professionalId) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        // profissional da equipe: sem filtro, a própria agenda; filtrar por outro é 403 (dados de clientes alheios)
        if (!actor.owner()) {
            if (professionalId == null) professionalId = actor.own();
            else actor.requireFor(professionalId);
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= MAX_AGENDA_DAYS) throw ApiException.badRequest(INVALID_PERIOD);
        ZoneId zone = tenants.zoneOf(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (from.isBefore(today.minusDays(HISTORY_DAYS)) || to.isAfter(today.plusDays(ScheduleService.HORIZON_DAYS))) {
            throw ApiException.badRequest(INVALID_PERIOD);
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Limit limit = Limit.of(MAX_AGENDA + 1);
        List<Booking> found = professionalId == null
                ? bookings.agenda(tenantId, start, end, limit)
                : bookings.agendaOf(tenantId, professionalId, start, end, limit);
        boolean truncated = found.size() > MAX_AGENDA;
        List<Booking> page = truncated ? found.subList(0, MAX_AGENDA) : found;
        return new AgendaView(views(tenantId, page, zone), truncated);
    }

    @Transactional
    public BookingView changeStatus(UUID tenantId, UUID id, StatusRequest r) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        Booking b = bookings.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        actor.requireFor(b.getProfessionalId());
        Instant now = clock.instant();
        if (b.getStatus() != BookingStatus.CONFIRMED || r.status() == BookingStatus.CONFIRMED) throw ApiException.conflict(INVALID_STATUS);
        if (r.status() != BookingStatus.CANCELLED && now.isBefore(b.getStartsAt())) throw ApiException.conflict(NOT_STARTED);
        // a leitura acima pode estar velha: quem decide é o UPDATE condicional (outro pedido pode ter finalizado antes)
        if (bookings.closeIfConfirmed(tenantId, id, r.status(), now) == 0) throw ApiException.conflict(INVALID_STATUS);
        b.changeStatus(r.status(), now);
        return views(tenantId, List.of(b), tenants.zoneOf(tenantId)).get(0);
    }

    /** Clientes numa consulta e expediente/bloqueios numa carga só: sem uma ida ao banco por agendamento. */
    private List<BookingView> views(UUID tenantId, List<Booking> list, ZoneId zone) {
        if (list.isEmpty()) return List.of();
        Map<UUID, Customer> byId = customers.findByTenantIdAndIdIn(tenantId, list.stream().map(Booking::getCustomerId).distinct().toList())
                .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        Instant from = list.get(0).getStartsAt();
        Instant to = list.stream().map(Booking::getBlockedUntil).max(Instant::compareTo).orElseThrow();
        WorkCalendar calendar = schedule.calendar(tenantId, zone, list.stream().map(Booking::getProfessionalId).distinct().toList(), from, to);
        return list.stream()
                .map(b -> view(b, byId.get(b.getCustomerId()), zone, !calendar.covers(b.getProfessionalId(), b.getStartsAt(), b.getBlockedUntil())))
                .toList();
    }

    private static BookingView view(Booking b, Customer c, ZoneId zone, boolean outsideHours) {
        return new BookingView(b.getId(), b.getProfessionalId(), b.getServiceId(),
                new CustomerView(c.getId(), c.getName(), c.getPhone(), c.getEmail()),
                b.getStartsAt().atZone(zone).toOffsetDateTime(), b.getEndsAt().atZone(zone).toOffsetDateTime(),
                b.getStatus(), b.getPriceCents(), b.getNote(), outsideHours);
    }
}
