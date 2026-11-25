package com.sskings.agendai.tenant;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TenantController {

    private final TenantService service;

    @GetMapping("/api/tenants/{id}")
    public TenantService.TenantView get(@PathVariable UUID id) {
        return service.get(id);
    }
}
