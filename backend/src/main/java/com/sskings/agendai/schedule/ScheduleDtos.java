package com.sskings.agendai.schedule;

import com.sskings.agendai.common.PlainText;
import com.sskings.agendai.common.Texts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ScheduleDtos {

    private ScheduleDtos() {}

    /** Data-hora sem deslocamento, no fuso do estabelecimento (ADR-008). */
    static final String LOCAL = "local-date-time";

    /** Hora do dia "HH:mm", de 00:00 a 24:00 (o fim do dia). */
    static final String HHMM = "([01]\\d|2[0-3]):[0-5]\\d|24:00";

    /** Faixa de trabalho num dia da semana (ISO: 1 = segunda … 7 = domingo), no fuso do estabelecimento. */
    public record WorkingInterval(
            @NotNull @Min(1) @Max(7) Integer weekday,
            @NotNull @Pattern(regexp = HHMM) String start,
            @NotNull @Pattern(regexp = HHMM) String end) {}

    /** A semana inteira do profissional: substitui a anterior. Lista vazia = sem expediente. */
    public record WorkingHoursRequest(@NotNull @Size(max = ScheduleService.MAX_INTERVALS) List<@NotNull @Valid WorkingInterval> intervals) {}

    public record WorkingHoursView(List<WorkingInterval> intervals) {}

    /** Início e fim no fuso do estabelecimento. Sem profissional = o estabelecimento inteiro (feriado). */
    public record TimeOffRequest(
            UUID professionalId,
            @NotNull @Schema(format = LOCAL, example = "2026-12-25T00:00") LocalDateTime start,
            @NotNull @Schema(format = LOCAL, example = "2026-12-26T00:00") LocalDateTime end,
            @Size(max = 100) @PlainText String reason) {
        public TimeOffRequest {
            reason = Texts.clean(reason);
            if (reason != null && reason.isEmpty()) reason = null;
        }
    }

    /** Sem profissional (null) = o estabelecimento inteiro; motivo pode faltar (null). */
    public record TimeOffView(UUID id, @Schema(nullable = true) UUID professionalId, @Schema(format = LOCAL) LocalDateTime start,
                              @Schema(format = LOCAL) LocalDateTime end,
                              @Schema(nullable = true) String reason) {}

    /** Inícios livres do dia, com o deslocamento do fuso (sem ambiguidade na troca de horário de verão). */
    public record AvailabilityView(LocalDate date, String timeZone, List<OffsetDateTime> slots) {}
}
