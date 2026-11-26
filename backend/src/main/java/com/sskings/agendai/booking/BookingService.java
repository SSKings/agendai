package com.sskings.agendai.booking;

import com.sskings.agendai.booking.BookingDtos.AgendaView;
import com.sskings.agendai.booking.BookingDtos.BookingRequest;
import com.sskings.agendai.booking.BookingDtos.BookingView;
import com.sskings.agendai.booking.BookingDtos.CustomerInput;
import com.sskings.agendai.booking.BookingDtos.CustomerView;
import com.sskings.agendai.booking.BookingDtos.StatusRequest;
import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.schedule.ScheduleService;
import com.sskings.agendai.schedule.WorkCalendar;
import com.sskings.agendai.tenant.TenantAccess;
import com.sskings.agendai.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
        ZoneId zone = tenants.zoneOf(tenantId);
        Instant now = clock.instant();
        Slot slot = slot(tenantId, r.professionalId(), offer, r.start(), zone, now, BookingSource.PANEL);
        String phone = digits(r.customer().phone());
        Customer customer = customer(tenantId, r.customer(), phone, now);
        Booking b = insert(new Booking(tenantId, r.professionalId(), r.serviceId(), customer.getId(), slot.start(), slot.end(),
                slot.blockedUntil(), offer.priceCents(), r.note(), now));
        BookingView view = view(b, customer, zone, false);
        // profissional da equipe: o e-mail de um cliente que já existia não volta para ele (pode ser de outro profissional)
        return actor.owner() ? view : withEmail(view, r.customer().email());
    }

    record Slot(Instant start, Instant end, Instant blockedUntil) {}

    /**
     * As regras de "dá para agendar aqui?" — painel e página pública: ativo, grade de 5 min, horário que existe no dia,
     * daqui para frente, dentro do horizonte, no expediente e fora dos bloqueios, e o teto diário do estabelecimento.
     */
    private Slot slot(UUID tenantId, UUID professionalId, CatalogService.Offer offer, LocalDateTime local, ZoneId zone, Instant now,
                      BookingSource source) {
        if (!offer.bookable()) throw ApiException.conflict(INACTIVE);
        if (local.getMinute() % 5 != 0 || local.getSecond() != 0 || local.getNano() != 0) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
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
        if (!schedule.calendar(tenantId, zone, List.of(professionalId), start, blockedUntil).covers(professionalId, start, blockedUntil)) {
            throw ApiException.conflict(OUTSIDE_HOURS);
        }
        // cada origem com o seu teto: robô na página não trava o painel do dono por 24 h
        if (bookings.countByTenantIdAndSourceAndCreatedAtAfter(tenantId, source, now.minus(Duration.ofDays(1))) >= MAX_CREATED_PER_DAY) {
            throw ApiException.conflict(DAILY_LIMIT);
        }
        if (source == BookingSource.PUBLIC && bookings.countOpenPending(tenantId, now) >= MAX_OPEN_PENDING) {
            throw ApiException.tooManyRequests(TOO_MANY_PENDING);
        }
        return new Slot(start, end, blockedUntil);
    }

    /** A restrição de exclusão decide (ADR-002): pendente vencido do profissional não deve bloquear — expira antes. */
    private Booking insert(Booking b) {
        bookings.expireStale(b.getTenantId(), b.getProfessionalId(), b.getCreatedAt());
        try {
            return bookings.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    // ---------- página pública (ADR-011): sem guard próprio, só depois do guard público ----------

    static final Duration CODE_VALIDITY = Duration.ofMinutes(10);
    /** Pendentes da página valendo ao mesmo tempo num estabelecimento. */
    static final int MAX_OPEN_PENDING = 50;
    static final String TOO_MANY_PENDING = "Too many pending bookings, try again later";
    static final int MAX_CODE_ATTEMPTS = 5;
    static final String INVALID_CODE = "Invalid code";
    static final String EXPIRED = "Booking expired";
    static final String TOO_MANY_ATTEMPTS = "Too many attempts";
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /** O pendente criado: o token (o cliente guarda) e o código (vai por e-mail) só existem aqui, em claro. */
    public record PendingBooking(UUID id, String token, String code, Instant pendingUntil, OffsetDateTime start, String serviceName,
                                 String professionalName) {}

    /** Visão do cliente: o horário que é dele, sem nada do estabelecimento além do necessário. */
    public record PublicBookingView(OffsetDateTime start, OffsetDateTime end, UUID serviceId, String serviceName, UUID professionalId,
                                    String professionalName, BookingStatus status) {}

    /**
     * Agendamento pela página pública: nasce PENDING (segura o horário por 10 min) com um código de 6 dígitos e um token
     * de acesso. Cliente por telefone sem a checagem de nome do painel: na página, um 409 ali seria consulta "esse
     * telefone é cliente?" (ADR-009); o código vai para o e-mail digitado agora, não para o cadastrado.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PendingBooking createPending(UUID tenantId, UUID professionalId, UUID serviceId, LocalDateTime local, CustomerInput c) {
        CatalogService.Offer offer = catalog.offer(tenantId, professionalId, serviceId);
        ZoneId zone = tenants.zoneOf(tenantId);
        Instant now = clock.instant();
        Slot slot = slot(tenantId, professionalId, offer, local, zone, now, BookingSource.PUBLIC);
        String phone = digits(c.phone());
        Customer customer = findOrCreate(tenantId, c, phone, now);
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        String token = Tokens.random();
        Instant until = now.plus(CODE_VALIDITY);
        Booking b = insert(Booking.pending(tenantId, professionalId, serviceId, customer.getId(), slot.start(), slot.end(),
                slot.blockedUntil(), offer.priceCents(), now, until, id -> codeHash(id, code), Tokens.sha256(token), c.name(), c.email()));
        return new PendingBooking(b.getId(), token, code, until, b.getStartsAt().atZone(zone).toOffsetDateTime(), offer.serviceName(),
                offer.professionalName());
    }

    /**
     * Confirma com o código. Até 5 tentativas (contadas por UPDATE atômico); errar a 5ª ou deixar vencer expira o
     * pendente e libera o horário. Confirmar de novo (clique duplo) devolve o mesmo agendamento.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)   // a tentativa errada fica contada
    public ClientBooking confirm(UUID tenantId, String token, String code) {
        Booking b = bookings.findByTenantIdAndAccessHash(tenantId, Tokens.sha256(token)).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Instant now = clock.instant();
        if (b.getStatus() == BookingStatus.CONFIRMED) return new ClientBooking(publicView(tenantId, b, BookingStatus.CONFIRMED), null);
        if (b.getStatus() != BookingStatus.PENDING || !now.isBefore(b.getPendingUntil())) throw ApiException.badRequest(EXPIRED);
        if (bookings.countAttempt(tenantId, b.getId(), MAX_CODE_ATTEMPTS) == 0) {
            bookings.closePending(tenantId, b.getId(), BookingStatus.EXPIRED, now);
            throw ApiException.badRequest(TOO_MANY_ATTEMPTS);
        }
        if (!java.security.MessageDigest.isEqual(codeHash(b.getId(), code).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getCodeHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            if (b.getCodeAttempts() + 1 >= MAX_CODE_ATTEMPTS) bookings.closePending(tenantId, b.getId(), BookingStatus.EXPIRED, now);
            throw ApiException.badRequest(INVALID_CODE);
        }
        if (bookings.closePending(tenantId, b.getId(), BookingStatus.CONFIRMED, now) == 0
                && bookings.statusOf(tenantId, b.getId()) != BookingStatus.CONFIRMED) {
            throw ApiException.badRequest(EXPIRED);   // venceu no meio; se o outro clique simultâneo confirmou, vale
        }
        return new ClientBooking(publicView(tenantId, b, BookingStatus.CONFIRMED), b.getContactEmail());
    }

    // ---------- link pessoal do cliente (2c): o token de 256 bits que só ele tem ----------

    static final String TOO_LATE = "Too late to change";
    static final String SAME_SERVICE = "Reschedule keeps the service";
    static final int MAX_RESCHEDULES = 3;
    static final String RESCHEDULE_LIMIT = "Reschedule limit reached";

    /** Visão do link pessoal: o agendamento e se ainda dá para cancelar/remarcar (até {@code changeUntil}). */
    public record ManagedView(PublicBookingView booking, boolean changeable, Instant changeUntil) {}

    /** Confirmado ou remarcado: a visão do cliente e o e-mail de contato (para o aviso, fora da transação). */
    public record ClientBooking(PublicBookingView view, String email) {}

    /** O link vale até o fim do atendimento; depois (ou token desconhecido), o mesmo 404. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ManagedView lookup(UUID tenantId, String token, Duration cancelUntil) {
        Booking b = byToken(tenantId, token);
        Instant until = b.getStartsAt().minus(cancelUntil);
        boolean changeable = b.getStatus() == BookingStatus.CONFIRMED && clock.instant().isBefore(until);
        return new ManagedView(publicView(tenantId, b, b.getStatus()), changeable, until);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ClientBooking cancelByClient(UUID tenantId, String token, Duration cancelUntil) {
        Booking b = changeable(tenantId, token, cancelUntil);
        if (bookings.closeIfConfirmed(tenantId, b.getId(), BookingStatus.CANCELLED, clock.instant()) == 0) {
            throw ApiException.conflict(INVALID_STATUS);
        }
        return new ClientBooking(publicView(tenantId, b, BookingStatus.CANCELLED), b.getContactEmail());
    }

    /**
     * Remarca para um horário já reservado (a página reserva antes, com as mesmas regras): cancela o antigo e cria o
     * novo na mesma transação — se o novo não couber, nada muda. O token passa para o novo.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClientBooking rescheduleByClient(UUID tenantId, String token, Duration cancelUntil, UUID professionalId, UUID serviceId,
                                            LocalDateTime start) {
        Booking old = changeable(tenantId, token, cancelUntil);
        if (!old.getServiceId().equals(serviceId)) throw ApiException.badRequest(SAME_SERVICE);
        // cada remarcação cria uma linha e um e-mail: sem limite, o link viraria gerador de linhas e gastaria os tetos
        if (old.getReschedules() >= MAX_RESCHEDULES) throw ApiException.conflict(RESCHEDULE_LIMIT);
        CatalogService.Offer offer = catalog.offer(tenantId, professionalId, serviceId);
        ZoneId zone = tenants.zoneOf(tenantId);
        Instant now = clock.instant();
        if (bookings.closeIfConfirmed(tenantId, old.getId(), BookingStatus.CANCELLED, now) == 0) throw ApiException.conflict(INVALID_STATUS);
        bookings.clearAccess(tenantId, old.getId());
        Slot slot = slot(tenantId, professionalId, offer, start, zone, now, BookingSource.PUBLIC);
        Booking b = insert(Booking.rescheduled(old, professionalId, slot.start(), slot.end(), slot.blockedUntil(), offer.priceCents(), now));
        return new ClientBooking(publicView(tenantId, b, BookingStatus.CONFIRMED), b.getContactEmail());
    }

    private Booking byToken(UUID tenantId, String token) {
        Booking b = bookings.findByTenantIdAndAccessHash(tenantId, Tokens.sha256(token)).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        if (!clock.instant().isBefore(b.getEndsAt())) throw ApiException.notFound(NOT_FOUND);
        return b;
    }

    /** Confirmado e ainda dentro do prazo da política (até {@code cancelUntil} antes do início). */
    private Booking changeable(UUID tenantId, String token, Duration cancelUntil) {
        Booking b = byToken(tenantId, token);
        if (b.getStatus() != BookingStatus.CONFIRMED) throw ApiException.conflict(INVALID_STATUS);
        if (!clock.instant().isBefore(b.getStartsAt().minus(cancelUntil))) throw ApiException.conflict(TOO_LATE);
        return b;
    }

    /** O e-mail do código não saiu: o pendente não fica segurando o horário (a reserva já foi usada). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandon(UUID tenantId, UUID bookingId) {
        bookings.closePending(tenantId, bookingId, BookingStatus.EXPIRED, clock.instant());
    }

    private PublicBookingView publicView(UUID tenantId, Booking b, BookingStatus status) {
        ZoneId zone = tenants.zoneOf(tenantId);
        CatalogService.Offer o = catalog.offer(tenantId, b.getProfessionalId(), b.getServiceId());
        return new PublicBookingView(b.getStartsAt().atZone(zone).toOffsetDateTime(), b.getEndsAt().atZone(zone).toOffsetDateTime(),
                b.getServiceId(), o.serviceName(), b.getProfessionalId(), o.professionalName(), status);
    }

    /** O código só vale para o seu agendamento: o hash leva o id junto. */
    static String codeHash(UUID bookingId, String code) {
        return Tokens.sha256(bookingId + ":" + code);
    }

    private static BookingView withEmail(BookingView v, String email) {
        CustomerView c = v.customer();
        return new BookingView(v.id(), v.professionalId(), v.serviceId(), new CustomerView(c.id(), c.name(), c.phone(), email, c.registeredName()),
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
        Customer found = findOrCreate(tenantId, c, phone, now);
        if (!found.getName().equalsIgnoreCase(c.name())) throw ApiException.conflict(PHONE_TAKEN);
        return found;
    }

    /**
     * O cadastro do telefone, criado se não existe. Se um "apagar" acabou de anonimizar o cadastro entre o INSERT (que
     * não fez nada) e a leitura, a leitura não acha: cria de novo, uma vez (o telefone agora está livre).
     */
    Customer findOrCreate(UUID tenantId, CustomerInput c, String phone, Instant now) {
        customers.insertIfAbsent(UUID.randomUUID(), tenantId, c.name(), phone, c.email(), now);
        java.util.Optional<Customer> found = customers.findByTenantIdAndPhone(tenantId, phone);
        if (found.isPresent()) return found.get();
        customers.insertIfAbsent(UUID.randomUUID(), tenantId, c.name(), phone, c.email(), now);
        return customers.findByTenantIdAndPhone(tenantId, phone).orElseThrow();
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

    /** Pela página, o nome e o e-mail mostrados são os de quem confirmou o código (o telefone liga ao cadastro). */
    private static BookingView view(Booking b, Customer c, ZoneId zone, boolean outsideHours) {
        // pela página e com o contato ainda lá (anonimizado: vale o cadastro, "Cliente removido")
        boolean online = b.getSource() == BookingSource.PUBLIC && b.getContactName() != null;
        return new BookingView(b.getId(), b.getProfessionalId(), b.getServiceId(),
                new CustomerView(c.getId(), online ? b.getContactName() : c.getName(), c.getPhone(), online ? b.getContactEmail() : c.getEmail(),
                        c.getName()),
                b.getStartsAt().atZone(zone).toOffsetDateTime(), b.getEndsAt().atZone(zone).toOffsetDateTime(),
                b.getStatus(), b.getPriceCents(), b.getNote(), outsideHours);
    }
}
