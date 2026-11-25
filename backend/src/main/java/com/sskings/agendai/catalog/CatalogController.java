package com.sskings.agendai.catalog;

import com.sskings.agendai.catalog.CatalogDtos.ProfessionalRequest;
import com.sskings.agendai.catalog.CatalogDtos.ProfessionalView;
import com.sskings.agendai.catalog.CatalogDtos.ServiceRequest;
import com.sskings.agendai.catalog.CatalogDtos.ServiceView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalog;

    @GetMapping("/services")
    public List<ServiceView> services(@PathVariable UUID tenantId) {
        return catalog.services(tenantId);
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceView createService(@PathVariable UUID tenantId, @Valid @RequestBody ServiceRequest body) {
        return catalog.createService(tenantId, body);
    }

    @PutMapping("/services/{id}")
    public ServiceView updateService(@PathVariable UUID tenantId, @PathVariable UUID id, @Valid @RequestBody ServiceRequest body) {
        return catalog.updateService(tenantId, id, body);
    }

    @GetMapping("/professionals")
    public List<ProfessionalView> professionals(@PathVariable UUID tenantId) {
        return catalog.professionals(tenantId);
    }

    @PostMapping("/professionals")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfessionalView createProfessional(@PathVariable UUID tenantId, @Valid @RequestBody ProfessionalRequest body) {
        return catalog.createProfessional(tenantId, body);
    }

    @PutMapping("/professionals/{id}")
    public ProfessionalView updateProfessional(@PathVariable UUID tenantId, @PathVariable UUID id,
                                               @Valid @RequestBody ProfessionalRequest body) {
        return catalog.updateProfessional(tenantId, id, body);
    }
}
