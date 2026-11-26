package com.sskings.agendai.publicbooking;

import com.sskings.agendai.booking.BookingService;
import com.sskings.agendai.catalog.CatalogService.PublicProfessional;
import com.sskings.agendai.publicbooking.PublicDtos.BookRequest;
import com.sskings.agendai.publicbooking.PublicDtos.ConfirmRequest;
import com.sskings.agendai.publicbooking.PublicDtos.HoldRequest;
import com.sskings.agendai.publicbooking.PublicDtos.HoldView;
import com.sskings.agendai.publicbooking.PublicDtos.PendingView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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

/** Rotas públicas (sem login, limite por IP e por estabelecimento no Redis): /api/public/{link do estabelecimento}/... */
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

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldView hold(@PathVariable String slug, @Valid @RequestBody HoldRequest body, HttpServletRequest request) {
        return pages.hold(slug, body, request.getRemoteAddr());
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public PendingView book(@PathVariable String slug, @Valid @RequestBody BookRequest body) {
        return pages.book(slug, body);
    }

    @PostMapping("/bookings/confirm")
    public BookingService.PublicBookingView confirm(@PathVariable String slug, @Valid @RequestBody ConfirmRequest body) {
        return pages.confirm(slug, body);
    }

    @GetMapping("/availability")
    public AvailabilityView availability(@PathVariable String slug, @RequestParam UUID professionalId, @RequestParam UUID serviceId,
                                         @RequestParam @DateTimeFormat(iso = DATE) LocalDate date) {
        return pages.availability(slug, professionalId, serviceId, date);
    }
}
