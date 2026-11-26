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

    @GetMapping("/api/tenants/{id}/policy")
    public TenantService.Policy policy(@PathVariable UUID id) {
        return service.policy(id);
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/tenants/{id}/policy")
    public TenantService.Policy updatePolicy(@PathVariable UUID id,
                                             @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody TenantService.Policy body) {
        return service.updatePolicy(id, body);
    }
}
