package com.sskings.agendai.booking;

import com.sskings.agendai.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingUnitTest {

    @Test
    @DisplayName("Só a violação da restrição de exclusão vira 409 'horário ocupado'; outra violação segue como está")
    void translate() {
        RuntimeException overlap = BookingService.translate(new DataIntegrityViolationException("x",
                new RuntimeException("conflicting key value violates exclusion constraint \"bookings_no_overlap\"")));
        assertThat(overlap).isInstanceOf(ApiException.class).hasMessage(BookingService.TAKEN);
        DataIntegrityViolationException other = new DataIntegrityViolationException("x", new RuntimeException("customers_phone_check"));
        assertThat(BookingService.translate(other)).isSameAs(other);
        DataIntegrityViolationException noMessage = new DataIntegrityViolationException(null);
        assertThat(BookingService.translate(noMessage)).isSameAs(noMessage);
    }

    @Test
    @DisplayName("Duração máxima de um agendamento = limites do catálogo (as consultas por período dependem disso)")
    void longest() {
        assertThat(BookingService.LONGEST).isEqualTo(java.time.Duration.ofMinutes(600));
        assertThat(BookingStatus.OCCUPYING).as("o mesmo predicado da restrição de exclusão (V4)")
                .containsExactlyInAnyOrder(BookingStatus.PENDING, BookingStatus.CONFIRMED, BookingStatus.COMPLETED, BookingStatus.NO_SHOW);
    }

    @Test
    @DisplayName("Telefone: só os dígitos, de 10 a 15")
    void digits() {
        assertThat(BookingService.digits("(11) 98765-4321")).isEqualTo("11987654321");
        assertThat(BookingService.digits("+1 202.555.0100")).isEqualTo("12025550100");
        assertThat(BookingService.digits("123456789012345")).hasSize(15);
        assertThatThrownBy(() -> BookingService.digits("123456789")).hasMessage(BookingService.INVALID_PHONE);
        assertThatThrownBy(() -> BookingService.digits("1234567890123456")).hasMessage(BookingService.INVALID_PHONE);
    }

    @Test
    @DisplayName("Agendamento: novo até ser gravado ou carregado; nasce confirmado e a mudança de status registra quando")
    void entity() {
        Instant now = Instant.parse("2026-11-30T12:00:00Z");
        Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(1800),
                now.plusSeconds(2400), 100, null, now);
        assertThat(b.isNew()).isTrue();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        b.changeStatus(BookingStatus.CANCELLED, now.plusSeconds(60));
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getUpdatedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(b.getCreatedAt()).isEqualTo(now);
        b.stored();
        assertThat(b.isNew()).isFalse();
    }

    @Test
    @DisplayName("Hash do código leva o id do agendamento: o mesmo código em outro agendamento dá outro hash")
    void codeHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(BookingService.codeHash(a, "123456")).hasSize(64).isEqualTo(BookingService.codeHash(a, "123456"));
        assertThat(BookingService.codeHash(a, "123456")).isNotEqualTo(BookingService.codeHash(b, "123456"))
                .isNotEqualTo(BookingService.codeHash(a, "123457"));
    }
}
