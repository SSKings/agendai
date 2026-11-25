package com.sskings.agendai.booking;

import com.sskings.agendai.booking.BookingDtos.AgendaView;
import com.sskings.agendai.booking.BookingDtos.BookingRequest;
import com.sskings.agendai.booking.BookingDtos.BookingView;
import com.sskings.agendai.booking.BookingDtos.StatusRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

@RestController
@RequestMapping("/api/tenants/{tenantId}/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookings;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView create(@PathVariable UUID tenantId, @Valid @RequestBody BookingRequest body) {
        return bookings.create(tenantId, body);
    }

    @GetMapping
    public AgendaView agenda(@PathVariable UUID tenantId,
                             @RequestParam @DateTimeFormat(iso = DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DATE) LocalDate to,
                             @RequestParam(required = false) UUID professionalId) {
        return bookings.agenda(tenantId, from, to, professionalId);
    }

    @PatchMapping("/{id}")
    public BookingView changeStatus(@PathVariable UUID tenantId, @PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
        return bookings.changeStatus(tenantId, id, body);
    }
}
