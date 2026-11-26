package com.sskings.agendai.publicbooking;

import com.sskings.agendai.common.PlainText;
import com.sskings.agendai.common.Texts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

public final class PublicDtos {

    private PublicDtos() {}

    /** Segurar um horário livre (início na data-hora local do estabelecimento, como os horários livres mostram). */
    public record HoldRequest(@NotNull UUID serviceId, @NotNull UUID professionalId,
                              @NotNull @Schema(format = "local-date-time", example = "2026-11-30T09:15") LocalDateTime start) {}

    /** {@code token}: guarde até concluir; vale até {@code expiresAt} (5 min). */
    public record HoldView(String token, Instant expiresAt) {}

    /** Quem agenda. E-mail obrigatório aqui: o código de confirmação vai para ele. */
    public record ClientInput(
            @NotBlank @Size(max = 100) @PlainText String name,
            @NotBlank @Pattern(regexp = "[0-9 ()+.\\-]{10,25}") String phone,
            @NotBlank @Email @Size(max = 254) @PlainText String email) {
        public ClientInput {
            name = Texts.clean(name);
            email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        }
    }

    public record BookRequest(@NotBlank @Size(max = 64) String holdToken, @NotNull @Valid ClientInput customer) {}

    /** O código foi para o e-mail; o {@code token} confirma (e, na 2c, cancela ou remarca). */
    public record PendingView(String token, Instant expiresAt) {}

    public record ConfirmRequest(@NotBlank @Size(max = 64) String token, @NotBlank @Pattern(regexp = "\\d{6}") String code) {}
}
