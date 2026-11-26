package com.sskings.agendai.booking;

import com.sskings.agendai.booking.BookingDtos.CustomerExport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Pedidos do titular dos dados (LGPD): só o dono do estabelecimento. */
@RestController
@RequestMapping("/api/tenants/{tenantId}/customers/{customerId}")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerPrivacy privacy;

    @GetMapping("/export")
    public CustomerExport export(@PathVariable UUID tenantId, @PathVariable UUID customerId) {
        return privacy.export(tenantId, customerId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(@PathVariable UUID tenantId, @PathVariable UUID customerId) {
        privacy.forget(tenantId, customerId);
    }
}
