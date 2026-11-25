package com.sskings.agendai.schedule;

import com.sskings.agendai.schedule.Slots.Span;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Expediente e bloqueios de um período, já carregados: responde "este intervalo cabe no expediente do profissional,
 * fora dos bloqueios?" sem ir ao banco por agendamento (agenda da semana, criação de agendamento). Faixas e bloqueios
 * ficam agrupados por profissional (e dia da semana) na construção: cada consulta só olha o que é daquele profissional.
 */
public final class WorkCalendar {

    private final ZoneId zone;
    private final Map<UUID, Map<Integer, List<WorkingHours>>> hours;
    private final Map<UUID, List<Span>> ownTimeOff;
    private final List<Span> everyone;

    WorkCalendar(ZoneId zone, List<WorkingHours> hours, List<TimeOff> timeOff) {
        this.zone = zone;
        this.hours = hours.stream().collect(Collectors.groupingBy(WorkingHours::getProfessionalId,
                Collectors.groupingBy(WorkingHours::getWeekday)));
        this.ownTimeOff = timeOff.stream().filter(t -> t.getProfessionalId() != null).collect(Collectors.groupingBy(
                TimeOff::getProfessionalId, Collectors.mapping(t -> new Span(t.getStartAt(), t.getEndAt()), Collectors.toList())));
        this.everyone = timeOff.stream().filter(t -> t.getProfessionalId() == null).map(t -> new Span(t.getStartAt(), t.getEndAt())).toList();
    }

    /** [start, end) inteiro dentro de uma faixa do dia local de {@code start}, e sem bloqueio do profissional ou de todos. */
    public boolean covers(UUID professionalId, Instant start, Instant end) {
        LocalDate date = start.atZone(zone).toLocalDate();
        boolean inShift = hours.getOrDefault(professionalId, Map.of()).getOrDefault(date.getDayOfWeek().getValue(), List.of()).stream()
                .anyMatch(w -> !start.isBefore(ScheduleService.wall(date, w.getStartMinute(), zone))
                        && !end.isAfter(ScheduleService.wall(date, w.getEndMinute(), zone)));
        if (!inShift) return false;
        Span span = new Span(start, end);
        return everyone.stream().noneMatch(span::overlaps)
                && ownTimeOff.getOrDefault(professionalId, List.of()).stream().noneMatch(span::overlaps);
    }
}
