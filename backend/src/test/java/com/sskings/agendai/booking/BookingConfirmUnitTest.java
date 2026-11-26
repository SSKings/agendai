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

    @Test
    @DisplayName("Remarcar quando o cancelamento chegou antes (o UPDATE condicional não pega): 409, nada muda")
    void rescheduleLosesToCancel() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        UUID tenant = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        String token = Tokens.random();
        Booking b = Booking.pending(tenant, UUID.randomUUID(), service, UUID.randomUUID(), now.plusSeconds(7 * 86400),
                now.plusSeconds(7 * 86400 + 1800), now.plusSeconds(7 * 86400 + 1800), 0, now, now.plusSeconds(600), id -> "h",
                Tokens.sha256(token), "Carla", "carla@x.com");
        b.changeStatus(BookingStatus.CONFIRMED, now);
        BookingRepository bookings = mock(BookingRepository.class);
        when(bookings.findByTenantIdAndAccessHash(tenant, Tokens.sha256(token))).thenReturn(Optional.of(b));
        when(bookings.closeIfConfirmed(eq(tenant), eq(b.getId()), eq(BookingStatus.CANCELLED), any())).thenReturn(0);
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.offer(any(), any(), any())).thenReturn(new CatalogService.Offer(30, 0, 0, true, "Corte", "João"));
        TenantService tenants = mock(TenantService.class);
        when(tenants.zoneOf(tenant)).thenReturn(java.time.ZoneId.of("America/Sao_Paulo"));
        BookingService service2 = new BookingService(mock(TenantAccess.class), tenants, catalog, mock(ScheduleService.class), bookings,
                mock(CustomerRepository.class), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> service2.rescheduleByClient(tenant, token, java.time.Duration.ofHours(24), UUID.randomUUID(), service,
                java.time.LocalDateTime.parse("2026-12-02T10:00"))).isInstanceOf(ApiException.class).hasMessage(BookingService.INVALID_STATUS);
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).clearAccess(any(), any());
    }

    // ---------- as corridas que o teste de integração só às vezes reproduz (no CI, às vezes não acontecem) ----------

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private static Booking confirmedIn(UUID tenant, String token) {
        Booking b = Booking.pending(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(7 * 86400),
                NOW.plusSeconds(7 * 86400 + 1800), NOW.plusSeconds(7 * 86400 + 1800), 0, NOW, NOW.plusSeconds(600),
                id -> BookingService.codeHash(id, "123456"), Tokens.sha256(token), "Carla", "carla@x.com");
        return b;
    }

    private static BookingService service(BookingRepository bookings, CatalogService catalog) {
        TenantService tenants = mock(TenantService.class);
        when(tenants.zoneOf(any())).thenReturn(java.time.ZoneId.of("America/Sao_Paulo"));
        return new BookingService(mock(TenantAccess.class), tenants, catalog, mock(ScheduleService.class), bookings,
                mock(CustomerRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CatalogService catalog() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.offer(any(), any(), any())).thenReturn(new CatalogService.Offer(30, 0, 0, true, "Corte", "João"));
        return catalog;
    }

    @Test
    @DisplayName("Confirmação simultânea: o UPDATE não pegou porque o outro clique já confirmou — devolve o confirmado")
    void confirmLosesToTwinClick() {
        UUID tenant = UUID.randomUUID();
        String token = Tokens.random();
        Booking b = confirmedIn(tenant, token);
        BookingRepository bookings = mock(BookingRepository.class);
        when(bookings.findByTenantIdAndAccessHash(tenant, Tokens.sha256(token))).thenReturn(Optional.of(b));
        when(bookings.countAttempt(eq(tenant), eq(b.getId()), anyInt())).thenReturn(1);
        when(bookings.closePending(eq(tenant), eq(b.getId()), eq(BookingStatus.CONFIRMED), any())).thenReturn(0);
        when(bookings.statusOf(tenant, b.getId())).thenReturn(BookingStatus.CONFIRMED);
        org.assertj.core.api.Assertions.assertThat(service(bookings, catalog()).confirm(tenant, token, "123456").view().status())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Cancelar pelo link quando outro pedido mudou o agendamento antes: 409")
    void clientCancelLosesRace() {
        UUID tenant = UUID.randomUUID();
        String token = Tokens.random();
        Booking b = confirmedIn(tenant, token);
        b.changeStatus(BookingStatus.CONFIRMED, NOW);
        BookingRepository bookings = mock(BookingRepository.class);
        when(bookings.findByTenantIdAndAccessHash(tenant, Tokens.sha256(token))).thenReturn(Optional.of(b));
        when(bookings.closeIfConfirmed(eq(tenant), eq(b.getId()), eq(BookingStatus.CANCELLED), any())).thenReturn(0);
        assertThatThrownBy(() -> service(bookings, catalog()).cancelByClient(tenant, token, java.time.Duration.ofHours(24)))
                .isInstanceOf(ApiException.class).hasMessage(BookingService.INVALID_STATUS);
    }

    @Test
    @DisplayName("Mudar status no painel quando outro pedido finalizou antes: 409")
    void panelStatusLosesRace() {
        UUID tenant = UUID.randomUUID();
        Booking b = confirmedIn(tenant, Tokens.random());
        b.changeStatus(BookingStatus.CONFIRMED, NOW);
        BookingRepository bookings = mock(BookingRepository.class);
        when(bookings.findByIdAndTenantId(b.getId(), tenant)).thenReturn(Optional.of(b));
        when(bookings.closeIfConfirmed(eq(tenant), eq(b.getId()), eq(BookingStatus.CANCELLED), any())).thenReturn(0);
        CatalogService catalog = catalog();
        when(catalog.actor(any(), any())).thenReturn(new CatalogService.Actor(true, null));
        assertThatThrownBy(() -> service(bookings, catalog).changeStatus(tenant, b.getId(),
                new BookingDtos.StatusRequest(BookingStatus.CANCELLED))).isInstanceOf(ApiException.class).hasMessage(BookingService.INVALID_STATUS);
    }

    @Test
    @DisplayName("Cadastro apagado entre o INSERT (que não fez nada) e a leitura: cria de novo, com o telefone já livre")
    void customerForgottenMidway() {
        UUID tenant = UUID.randomUUID();
        Customer fresh = mock(Customer.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findByTenantIdAndPhone(tenant, "11987654321")).thenReturn(Optional.empty()).thenReturn(Optional.of(fresh));
        BookingService s = new BookingService(mock(TenantAccess.class), mock(TenantService.class), catalog(), mock(ScheduleService.class),
                mock(BookingRepository.class), customers, Clock.fixed(NOW, ZoneOffset.UTC));
        org.assertj.core.api.Assertions.assertThat(s.findOrCreate(tenant,
                new BookingDtos.CustomerInput("Bruno Lima", "11987654321", null), "11987654321", NOW)).isSameAs(fresh);
        org.mockito.Mockito.verify(customers, org.mockito.Mockito.times(2))
                .insertIfAbsent(any(), eq(tenant), eq("Bruno Lima"), eq("11987654321"), eq(null), eq(NOW));
    }
}

