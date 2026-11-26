package com.sskings.agendai.booking;

import com.sskings.agendai.booking.BookingDtos.CustomerExport;
import com.sskings.agendai.booking.BookingDtos.ExportedBooking;
import com.sskings.agendai.booking.BookingDtos.ExportedCustomer;
import com.sskings.agendai.catalog.CatalogDtos.ProfessionalView;
import com.sskings.agendai.catalog.CatalogDtos.ServiceView;
import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.tenant.TenantAccess;
import com.sskings.agendai.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Direitos do titular (LGPD, docs/seguranca.md). O estabelecimento é o controlador: o cliente pede a ele, e só o DONO
 * exporta ou apaga (o resto da equipe vê a agenda, não responde por pedidos de dados). Apagar = anonimizar: o cadastro e
 * os agendamentos perdem nome, telefone, e-mail, observação e o link pessoal; a agenda e o caixa continuam de pé.
 */
@Service
@RequiredArgsConstructor
public class CustomerPrivacy {

    static final String NOT_FOUND = "Customer not found";
    static final String UPCOMING = "Customer has upcoming bookings";
    static final int MAX_EXPORTED = 1000;

    private final TenantAccess access;
    private final TenantService tenants;
    private final CatalogService catalog;
    private final BookingRepository bookings;
    private final CustomerRepository customers;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CustomerExport export(UUID tenantId, UUID customerId) {
        access.ownerReading(tenantId);   // leitura: sem a trava das escritas
        Customer c = customers.findByIdAndTenantId(customerId, tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        ZoneId zone = tenants.zoneOf(tenantId);
        Map<UUID, String> services = catalog.services(tenantId).stream().collect(Collectors.toMap(ServiceView::id, ServiceView::name));
        Map<UUID, String> professionals = catalog.professionals(tenantId).stream()
                .collect(Collectors.toMap(ProfessionalView::id, ProfessionalView::name));
        // um a mais que o teto: diz se cortou
        List<Booking> list = bookings.findByTenantIdAndCustomerIdOrderByStartsAtDesc(tenantId, customerId, Limit.of(MAX_EXPORTED + 1));
        List<ExportedBooking> exported = list.stream().limit(MAX_EXPORTED).map(b -> new ExportedBooking(
                b.getStartsAt().atZone(zone).toOffsetDateTime(), b.getEndsAt().atZone(zone).toOffsetDateTime(),
                services.get(b.getServiceId()), professionals.get(b.getProfessionalId()), b.getStatus(), b.getSource(),
                b.getPriceCents(), titular(c, b) ? b.getContactName() : null, titular(c, b) ? b.getContactEmail() : null,
                b.getContactName() != null && !titular(c, b), b.getNote(), b.getCreatedAt())).toList();
        return new CustomerExport(clock.instant(), tenants.get(tenantId).name(),
                new ExportedCustomer(c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getCreatedAt(), c.getAnonymizedAt()),
                exported, list.size() > MAX_EXPORTED);
    }

    /**
     * O contato registrado pela página é do titular? A página liga o agendamento ao cadastro pelo TELEFONE: outra pessoa
     * (um familiar, um dígito errado) pode ter agendado com ele. Contato com nome e e-mail diferentes do cadastro é de um
     * terceiro e não vai na exportação (fica só a marca {@code otherContact}).
     */
    static boolean titular(Customer c, Booking b) {
        if (b.getContactName() == null) return false;
        return b.getContactName().equalsIgnoreCase(c.getName())
                || (c.getEmail() != null && c.getEmail().equalsIgnoreCase(b.getContactEmail()));
    }

    /**
     * Anonimiza o cliente. Com agendamento ainda por acontecer (pendente ou confirmado), 409: cancele antes — apagar o
     * contato de quem ainda vai ser atendido deixaria a agenda sem como avisar. Repetir é inofensivo (204 de novo).
     */
    @Transactional
    public void forget(UUID tenantId, UUID customerId) {
        access.owner(tenantId);
        // trava a linha: quem está agendando com este cadastro (FOR SHARE) termina antes, e o agendamento dele conta abaixo
        customers.lockForForget(customerId, tenantId).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Instant now = clock.instant();
        if (bookings.existsByTenantIdAndCustomerIdAndStatusInAndStartsAtAfter(tenantId, customerId,
                List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED), now)) {
            throw ApiException.conflict(UPCOMING);
        }
        customers.anonymize(tenantId, customerId, now);
        bookings.forgetContact(tenantId, customerId, now);
    }

}
