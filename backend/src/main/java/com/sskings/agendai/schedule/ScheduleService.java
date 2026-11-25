package com.sskings.agendai.schedule;

import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import com.sskings.agendai.schedule.ScheduleDtos.TimeOffRequest;
import com.sskings.agendai.schedule.ScheduleDtos.TimeOffView;
import com.sskings.agendai.schedule.ScheduleDtos.WorkingHoursRequest;
import com.sskings.agendai.schedule.ScheduleDtos.WorkingHoursView;
import com.sskings.agendai.schedule.ScheduleDtos.WorkingInterval;
import com.sskings.agendai.schedule.Slots.Span;
import com.sskings.agendai.tenant.TenantAccess;
import com.sskings.agendai.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Expediente, bloqueios e horários livres. Datas e horas da API são do fuso do estabelecimento; no banco, tudo em UTC
 * (docs/arquitetura.md, "Datas e fusos"). Membro lê; só o dono altera, com a trava de escrita do estabelecimento.
 */
@Component
@RequiredArgsConstructor
public class ScheduleService {

    static final int MAX_INTERVALS = 28;               // 4 faixas por dia, 7 dias
    static final int MAX_UPCOMING_TIME_OFF = 200;      // bloqueios que ainda não terminaram, por estabelecimento
    static final int MAX_PERIOD_DAYS = 92;             // listagem e duração de um bloqueio
    static final int MAX_LISTED_TIME_OFF = 500;
    /** Até quantos dias à frente se consulta horário livre ou se agenda (teto técnico; ADR-008). */
    public static final int HORIZON_DAYS = 180;
    static final int HISTORY_DAYS = 366;               // listagem de bloqueios: até um ano para trás…
    static final int FUTURE_DAYS = 2 * 366;            // …e dois para a frente (também o fim máximo de um bloqueio)
    /** Duração máxima de um bloqueio; as consultas por período contam com ela para limitar a faixa lida do índice. */
    static final Duration LONGEST_TIME_OFF = Duration.ofDays(MAX_PERIOD_DAYS);

    static final String END_BEFORE_START = "End must be after start";
    static final String OVERLAP = "Overlapping working hours";
    static final String FIVE_MINUTE_STEPS = "Times must be multiples of 5 minutes";
    static final String TIME_OFF_NOT_FOUND = "Time off not found";
    static final String ALREADY_ENDED = "Time off already ended";
    static final String TOO_LONG = "Time off longer than 92 days";
    static final String TIME_OFF_LIMIT = "Time off limit reached";
    static final String INVALID_PERIOD = "Invalid period";
    static final String DATE_OUT_OF_RANGE = "Date out of range";

    private final TenantAccess access;
    private final TenantService tenants;
    private final CatalogService catalog;
    private final WorkingHoursRepository hours;
    private final TimeOffRepository timeOff;
    private final Clock clock;
    private final List<BusyPeriods> busyPeriods;

    // ---------- expediente ----------

    @Transactional(readOnly = true)
    public WorkingHoursView workingHours(UUID tenantId, UUID professionalId) {
        access.member(tenantId);
        catalog.requireProfessional(tenantId, professionalId);
        return view(hours.weekOf(tenantId, professionalId));
    }

    @Transactional
    public WorkingHoursView replaceWorkingHours(UUID tenantId, UUID professionalId, WorkingHoursRequest r) {
        access.owner(tenantId);
        catalog.requireProfessional(tenantId, professionalId);
        List<WorkingHours> week = new ArrayList<>();
        for (WorkingInterval i : r.intervals()) {
            int start = minutes(i.start());
            int end = minutes(i.end());
            if (start % 5 != 0 || end % 5 != 0) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
            if (end <= start) throw ApiException.badRequest(END_BEFORE_START);
            week.add(new WorkingHours(tenantId, professionalId, i.weekday(), start, end));
        }
        week.sort(Comparator.comparingInt(WorkingHours::getWeekday).thenComparingInt(WorkingHours::getStartMinute));
        for (int k = 1; k < week.size(); k++) {
            WorkingHours prev = week.get(k - 1);
            WorkingHours cur = week.get(k);
            if (prev.getWeekday() == cur.getWeekday() && cur.getStartMinute() < prev.getEndMinute()) {
                throw ApiException.badRequest(OVERLAP);
            }
        }
        hours.deleteOf(tenantId, professionalId);      // DELETE em lote: executa na hora, antes das novas faixas
        hours.saveAll(week);
        return view(week);
    }

    private static WorkingHoursView view(List<WorkingHours> week) {
        return new WorkingHoursView(week.stream()
                .map(w -> new WorkingInterval(w.getWeekday(), hhmm(w.getStartMinute()), hhmm(w.getEndMinute())))
                .toList());
    }

    /** "HH:mm" (já validado pelo padrão) → minutos desde 00:00. */
    static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }

    static String hhmm(int minutes) {
        return "%02d:%02d".formatted(minutes / 60, minutes % 60);
    }

    // ---------- bloqueios ----------

    /** Bloqueios que encostam nos dias de {@code from} a {@code to} (inclusive), no fuso do estabelecimento. */
    @Transactional(readOnly = true)
    public List<TimeOffView> timeOff(UUID tenantId, LocalDate from, LocalDate to) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= MAX_PERIOD_DAYS) throw ApiException.badRequest(INVALID_PERIOD);
        ZoneId zone = tenants.zoneOf(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (from.isBefore(today.minusDays(HISTORY_DAYS)) || to.isAfter(today.plusDays(FUTURE_DAYS))) throw ApiException.badRequest(INVALID_PERIOD);
        Instant start = from.atStartOfDay(zone).toInstant();
        return timeOff.overlapping(tenantId, start.minus(LONGEST_TIME_OFF), start, to.plusDays(1).atStartOfDay(zone).toInstant(),
                        Limit.of(MAX_LISTED_TIME_OFF))
                .stream().map(t -> view(t, zone, actor.actsOn(t.getProfessionalId())))
                .toList();
    }

    @Transactional
    public TimeOffView createTimeOff(UUID tenantId, TimeOffRequest r) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        if (r.professionalId() != null) catalog.requireProfessional(tenantId, r.professionalId());
        // profissional da equipe bloqueia só a própria agenda (folga, almoço); feriado do estabelecimento é do dono
        if (!actor.actsOn(r.professionalId())) throw ApiException.forbidden(CatalogService.OWN_AGENDA);
        access.lock(tenantId);
        if (!onStep(r.start()) || !onStep(r.end())) throw ApiException.badRequest(FIVE_MINUTE_STEPS);
        ZoneId zone = tenants.zoneOf(tenantId);
        if (r.end().toLocalDate().isAfter(LocalDate.now(clock.withZone(zone)).plusDays(FUTURE_DAYS))) {
            throw ApiException.badRequest(DATE_OUT_OF_RANGE);   // nada "para sempre" ocupando o teto dos futuros
        }
        Instant start = r.start().atZone(zone).toInstant();
        Instant end = r.end().atZone(zone).toInstant();
        Instant now = clock.instant();
        if (!end.isAfter(start)) throw ApiException.badRequest(END_BEFORE_START);
        if (!end.isAfter(now)) throw ApiException.badRequest(ALREADY_ENDED);
        if (Duration.between(start, end).compareTo(LONGEST_TIME_OFF) > 0) throw ApiException.badRequest(TOO_LONG);
        if (timeOff.countByTenantIdAndEndAtAfter(tenantId, now) >= MAX_UPCOMING_TIME_OFF) throw ApiException.conflict(TIME_OFF_LIMIT);
        return view(timeOff.save(new TimeOff(tenantId, r.professionalId(), start, end, r.reason(), now)), zone, true);
    }

    @Transactional
    public void deleteTimeOff(UUID tenantId, UUID id) {
        CatalogService.Actor actor = catalog.actor(tenantId, access.member(tenantId));
        TimeOff t = timeOff.findByIdAndTenantId(id, tenantId).orElseThrow(() -> ApiException.notFound(TIME_OFF_NOT_FOUND));
        if (!actor.actsOn(t.getProfessionalId())) throw ApiException.forbidden(CatalogService.OWN_AGENDA);
        access.lock(tenantId);
        timeOff.delete(t);
    }

    private static boolean onStep(LocalDateTime t) {
        return t.getMinute() % 5 == 0 && t.getSecond() == 0 && t.getNano() == 0;
    }

    /** O motivo pode ser pessoal ("médico"): só o dono e o próprio profissional veem; os outros, só o período. */
    private static TimeOffView view(TimeOff t, ZoneId zone, boolean withReason) {
        return new TimeOffView(t.getId(), t.getProfessionalId(), LocalDateTime.ofInstant(t.getStartAt(), zone),
                LocalDateTime.ofInstant(t.getEndAt(), zone), withReason ? t.getReason() : null);
    }

    // ---------- horários livres ----------

    /**
     * Inícios livres de um serviço com um profissional num dia (do fuso do estabelecimento): dentro do expediente,
     * com atendimento + preparo cabendo inteiros, fora dos bloqueios e a partir de agora.
     */
    @Transactional(readOnly = true)
    public AvailabilityView availability(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date) {
        access.member(tenantId);
        return slots(tenantId, professionalId, serviceId, date);
    }

    /**
     * Os horários livres sem guard próprio: só dentro de uma transação que já passou por um (o do painel, acima, ou o
     * da página pública). Mesma regra para os dois.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AvailabilityView slots(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date) {
        CatalogService.Offer offer = catalog.offer(tenantId, professionalId, serviceId);
        ZoneId zone = tenants.zoneOf(tenantId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (date.isBefore(today) || date.isAfter(today.plusDays(HORIZON_DAYS))) throw ApiException.badRequest(DATE_OUT_OF_RANGE);
        List<OffsetDateTime> slots = offer.bookable() ? free(tenantId, professionalId, date, zone, offer) : List.of();
        return new AvailabilityView(date, zone.getId(), slots);
    }

    private List<OffsetDateTime> free(UUID tenantId, UUID professionalId, LocalDate date, ZoneId zone, CatalogService.Offer offer) {
        List<Span> working = hours.dayOf(tenantId, professionalId, date.getDayOfWeek().getValue()).stream()
                .map(w -> new Span(wall(date, w.getStartMinute(), zone), wall(date, w.getEndMinute(), zone)))
                .toList();
        if (working.isEmpty()) return List.of();
        Instant first = working.get(0).start();
        Instant now = clock.instant();
        // o que terminou antes de agora não tira horário (nenhum começa no passado): os bloqueios lidos são no máximo
        // os ainda não terminados, que têm teto
        Instant from = first.isAfter(now) ? first : now;
        Instant to = working.get(working.size() - 1).end();
        List<Span> busy = new ArrayList<>();
        timeOff.blocking(tenantId, professionalId, first.minus(LONGEST_TIME_OFF), from, to)
                .forEach(t -> busy.add(new Span(t.getStartAt(), t.getEndAt())));
        for (BusyPeriods p : busyPeriods) {
            p.busy(tenantId, professionalId, from, to).forEach(b -> busy.add(new Span(b.start(), b.end())));
        }
        Duration block = Duration.ofMinutes(offer.durationMinutes() + offer.bufferMinutes());
        return Slots.free(working, busy, block, now).stream()
                .map(t -> t.atZone(zone).toOffsetDateTime())
                .toList();
    }

    // ---------- para outros módulos (agendamento) ----------

    /**
     * Expediente dos profissionais e bloqueios que encostam em [from, to), para consultas em memória. Só dentro de uma
     * transação que já passou pelo guard (sem ele, o RLS esconde tudo e o calendário vem vazio).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WorkCalendar calendar(UUID tenantId, ZoneId zone, Collection<UUID> professionalIds, Instant from, Instant to) {
        // só os bloqueios desses profissionais e os gerais; o teto é uma rede de segurança (os que ainda não terminaram
        // são no máximo 200 — passado distante só aparece na agenda de semanas antigas)
        List<TimeOff> off = timeOff.blockingAny(tenantId, professionalIds, from.minus(LONGEST_TIME_OFF), from, to,
                Limit.of(MAX_LISTED_TIME_OFF));
        return new WorkCalendar(zone, hours.findByTenantIdAndProfessionalIdIn(tenantId, professionalIds), off);
    }

    /** Hora de relógio na parede do estabelecimento → instante. 1440 min = 00:00 do dia seguinte. */
    static Instant wall(LocalDate date, int minute, ZoneId zone) {
        LocalDateTime t = minute == 24 * 60 ? date.plusDays(1).atStartOfDay() : date.atTime(LocalTime.ofSecondOfDay(minute * 60L));
        return t.atZone(zone).toInstant();
    }
}
