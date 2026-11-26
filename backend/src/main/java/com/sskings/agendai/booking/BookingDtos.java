package com.sskings.agendai.booking;

import com.sskings.agendai.common.PlainText;
import com.sskings.agendai.common.Texts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {}

    /** Telefone como as pessoas digitam: dígitos, espaços, parênteses, "+", "-" e "."; 10 a 15 dígitos (conferido no serviço). */
    static final String PHONE = "[0-9 ()+.\\-]{10,25}";

    public record CustomerInput(
            @NotBlank @Size(max = 100) @PlainText String name,
            @NotBlank @Pattern(regexp = PHONE) String phone,
            @Email @Size(max = 254) @PlainText String email) {
        public CustomerInput {
            name = Texts.clean(name);
            email = email == null || email.isBlank() ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Agendamento manual pelo painel. Início na data-hora local do estabelecimento (ADR-008). */
    public record BookingRequest(
            @NotNull UUID professionalId,
            @NotNull UUID serviceId,
            @NotNull @Schema(format = "local-date-time", example = "2026-11-30T09:15") LocalDateTime start,
            @NotNull @Valid CustomerInput customer,
            @Size(max = 200) @PlainText String note) {
        public BookingRequest {
            note = Texts.clean(note);
            if (note != null && note.isEmpty()) note = null;
        }
    }

    public record StatusRequest(@NotNull BookingStatus status) {}

    /** Telefone nulo: cliente anonimizado (LGPD). */
    /**
     * {@code name}/{@code email}: quem agendou (pela página, o contato digitado). {@code registeredName}: o cadastro a que
     * o telefone liga — é sobre ele que agem a exportação e a exclusão (LGPD). Telefone nulo: anonimizado.
     */
    public record CustomerView(UUID id, String name, @Schema(nullable = true) String phone, @Schema(nullable = true) String email,
                               String registeredName) {}

    /** Exportação LGPD: o cadastro e os agendamentos (com o contato que a página registrou em cada um). */
    public record CustomerExport(Instant exportedAt, String business, ExportedCustomer customer, List<ExportedBooking> bookings,
                                 boolean truncated) {}

    public record ExportedCustomer(UUID id, String name, @Schema(nullable = true) String phone, @Schema(nullable = true) String email,
                                   Instant createdAt, @Schema(nullable = true) Instant anonymizedAt) {}

    public record ExportedBooking(OffsetDateTime start, OffsetDateTime end, String serviceName, String professionalName,
                                  BookingStatus status, BookingSource source, int priceCents,
                                  @Schema(nullable = true) String contactName, @Schema(nullable = true) String contactEmail,
                                  boolean otherContact, @Schema(nullable = true) String note, Instant createdAt) {}

    /**
     * {@code end}: fim do atendimento (o preparo depois não aparece). {@code outsideHours}: o expediente ou um bloqueio
     * mudou depois de agendado — o agendamento continua, para o dono decidir (docs/produto.md).
     */
    public record BookingView(UUID id, UUID professionalId, UUID serviceId, CustomerView customer, OffsetDateTime start,
                              OffsetDateTime end, BookingStatus status, int priceCents, @Schema(nullable = true) String note,
                              boolean outsideHours) {}

    /** {@code truncated}: havia mais que o teto da resposta (filtre por profissional ou por um dia). */
    public record AgendaView(List<BookingView> bookings, boolean truncated) {}
}
