package com.sskings.agendai.publicbooking;

import com.sskings.agendai.catalog.CatalogService.PublicProfessional;
import com.sskings.agendai.catalog.CatalogService.PublicService;
import com.sskings.agendai.publicbooking.PublicBookingService.BusinessView;
import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

/** Rotas públicas (sem login, limite por IP no Redis): /api/public/{link do estabelecimento}/... */
@RestController
@RequestMapping("/api/public/{slug}")
@RequiredArgsConstructor
public class PublicBookingController {

    private final PublicBookingService pages;

    @GetMapping
    public BusinessView business(@PathVariable String slug) {
        return pages.business(slug);
    }

    @GetMapping("/services")
    public List<PublicService> services(@PathVariable String slug) {
        return pages.services(slug);
    }

    @GetMapping("/services/{serviceId}/professionals")
    public List<PublicProfessional> professionals(@PathVariable String slug, @PathVariable UUID serviceId) {
        return pages.professionals(slug, serviceId);
    }

    @GetMapping("/availability")
    public AvailabilityView availability(@PathVariable String slug, @RequestParam UUID professionalId, @RequestParam UUID serviceId,
                                         @RequestParam @DateTimeFormat(iso = DATE) LocalDate date) {
        return pages.availability(slug, professionalId, serviceId, date);
    }
}
