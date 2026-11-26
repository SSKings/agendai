package com.sskings.agendai.booking;

import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.schedule.ScheduleService;
import com.sskings.agendai.tenant.TenantAccess;
import com.sskings.agendai.tenant.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A corrida que o teste de integração não reproduz sempre: o pendente vence entre a conferência e o UPDATE. */
class BookingConfirmUnitTest {

    @Test
    @DisplayName("Código certo, mas o pendente expirou no meio (o UPDATE não pegou e ele não está confirmado): 'Booking expired'")
    void expiredInBetween() {
        Instant now = Instant.parse("2026-11-30T12:00:00Z");
        UUID tenant = UUID.randomUUID();
        String token = Tokens.random();
        Booking b = Booking.pending(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(3600),
                now.plusSeconds(5400), now.plusSeconds(5400), 0, now, now.plusSeconds(600), id -> BookingService.codeHash(id, "123456"),
                Tokens.sha256(token), "Carla", "carla@x.com");
        BookingRepository bookings = mock(BookingRepository.class);
        when(bookings.findByTenantIdAndAccessHash(tenant, Tokens.sha256(token))).thenReturn(Optional.of(b));
        when(bookings.countAttempt(eq(tenant), eq(b.getId()), anyInt())).thenReturn(1);
        when(bookings.closePending(eq(tenant), eq(b.getId()), eq(BookingStatus.CONFIRMED), any())).thenReturn(0);
        when(bookings.statusOf(tenant, b.getId())).thenReturn(BookingStatus.EXPIRED);
        BookingService service = new BookingService(mock(TenantAccess.class), mock(TenantService.class), mock(CatalogService.class),
                mock(ScheduleService.class), bookings, mock(CustomerRepository.class), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.confirm(tenant, token, "123456")).isInstanceOf(ApiException.class)
                .hasMessage(BookingService.EXPIRED);
    }
}
