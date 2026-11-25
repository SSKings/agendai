package com.sskings.agendai.schedule;

import com.sskings.agendai.schedule.Slots.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleUnitTest {

    static final Instant T0 = Instant.parse("2026-11-30T12:00:00Z");

    static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    static Span span(int from, int to) {
        return new Span(at(from), at(to));
    }

    @Test
    @DisplayName("Intervalos semiabertos: encostar não é sobrepor; conter, cruzar ou ser igual é")
    void overlaps() {
        assertThat(span(0, 60).overlaps(span(60, 90))).isFalse();
        assertThat(span(60, 90).overlaps(span(0, 60))).isFalse();
        assertThat(span(0, 60).overlaps(span(59, 90))).isTrue();
        assertThat(span(30, 90).overlaps(span(0, 31))).isTrue();
        assertThat(span(0, 60).overlaps(span(10, 20))).isTrue();
        assertThat(span(10, 20).overlaps(span(0, 60))).isTrue();
        assertThat(span(0, 60).overlaps(span(0, 60))).isTrue();
    }

    @Test
    @DisplayName("Horários de 15 em 15 min a partir do início da faixa; o bloco inteiro cabe; nada antes de 'agora'")
    void free() {
        Duration block = Duration.ofMinutes(40);
        assertThat(Slots.free(List.of(span(0, 70)), List.of(), block, at(0))).containsExactly(at(0), at(15), at(30));
        assertThat(Slots.free(List.of(span(0, 40)), List.of(), block, at(0))).as("cabe exato").containsExactly(at(0));
        assertThat(Slots.free(List.of(span(0, 39)), List.of(), block, at(0))).isEmpty();
        assertThat(Slots.free(List.of(span(0, 70)), List.of(), block, at(15))).as("começando agora vale").containsExactly(at(15), at(30));
        assertThat(Slots.free(List.of(span(0, 70)), List.of(), block, at(16))).containsExactly(at(30));
        assertThat(Slots.free(List.of(span(0, 100)), List.of(span(40, 55)), block, at(0)))
                .as("ocupado no meio").containsExactly(at(0), at(60));
        assertThat(Slots.free(List.of(span(5, 50), span(100, 140)), List.of(), block, at(0))).containsExactly(at(5), at(100));
        assertThat(Slots.free(List.of(), List.of(), block, at(0))).isEmpty();
    }

    @Test
    @DisplayName("HH:mm ↔ minutos, incluindo o fim do dia (24:00)")
    void clock() {
        assertThat(ScheduleService.minutes("00:00")).isZero();
        assertThat(ScheduleService.minutes("09:35")).isEqualTo(575);
        assertThat(ScheduleService.minutes("24:00")).isEqualTo(1440);
        assertThat(ScheduleService.hhmm(0)).isEqualTo("00:00");
        assertThat(ScheduleService.hhmm(575)).isEqualTo("09:35");
        assertThat(ScheduleService.hhmm(1440)).isEqualTo("24:00");
    }

    @Test
    @DisplayName("Hora da parede no fuso → instante, com o fim do dia sendo a meia-noite seguinte e o horário de verão respeitado")
    void wall() {
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        LocalDate monday = LocalDate.parse("2026-11-30");
        assertThat(ScheduleService.wall(monday, 0, sp)).isEqualTo(Instant.parse("2026-11-30T03:00:00Z"));
        assertThat(ScheduleService.wall(monday, 21 * 60, sp)).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(ScheduleService.wall(monday, 1440, sp)).isEqualTo(Instant.parse("2026-12-01T03:00:00Z"));
        // Nova York, 08/03/2026: 02:00 vira 03:00 — o dia tem 23 h
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate dst = LocalDate.parse("2026-03-08");
        assertThat(Duration.between(ScheduleService.wall(dst, 0, ny), ScheduleService.wall(dst, 1440, ny))).isEqualTo(Duration.ofHours(23));
        assertThat(ScheduleService.wall(dst, 9 * 60, ny)).isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
    }

    @Test
    @DisplayName("Expediente e bloqueio: novos ao criar; deixam de ser novos depois de gravados ou carregados")
    void newUntilStored() {
        UUID tenant = UUID.randomUUID();
        WorkingHours w = new WorkingHours(tenant, UUID.randomUUID(), 1, 540, 720);
        TimeOff t = new TimeOff(tenant, null, T0, at(60), null, T0);
        assertThat(w.isNew()).isTrue();
        assertThat(t.isNew()).isTrue();
        w.stored();
        t.stored();
        assertThat(w.isNew()).isFalse();
        assertThat(t.isNew()).isFalse();
    }

    @Test
    @DisplayName("Horário de verão: hora que não existe vai para depois do salto; hora repetida aparece duas vezes, com deslocamentos diferentes")
    void daylightSaving() {
        ZoneId ny = ZoneId.of("America/New_York");
        // 08/03/2026: 02:30 não existe → 03:30 EDT (07:30Z)
        assertThat(ScheduleService.wall(LocalDate.parse("2026-03-08"), 150, ny)).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        // 01/11/2026: 01:00–02:00 acontece duas vezes; uma faixa de 00:00 a 03:00 tem 4 horas reais
        LocalDate fallBack = LocalDate.parse("2026-11-01");
        Span night = new Span(ScheduleService.wall(fallBack, 0, ny), ScheduleService.wall(fallBack, 180, ny));
        assertThat(Duration.between(night.start(), night.end())).isEqualTo(Duration.ofHours(4));
        List<String> local = Slots.free(List.of(night), List.of(), Duration.ofMinutes(60), Instant.EPOCH).stream()
                .map(t -> t.atZone(ny).toOffsetDateTime().toString()).toList();
        assertThat(local).contains("2026-11-01T01:00-04:00", "2026-11-01T01:00-05:00");
        // São Paulo, 04/11/2018: a meia-noite não existiu (00:00 → 01:00); o fim do dia 3 é o começo real do dia 4
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        assertThat(ScheduleService.wall(LocalDate.parse("2018-11-03"), 1440, sp))
                .isEqualTo(ScheduleService.wall(LocalDate.parse("2018-11-04"), 0, sp))
                .isEqualTo(Instant.parse("2018-11-04T03:00:00Z"));
    }
}
