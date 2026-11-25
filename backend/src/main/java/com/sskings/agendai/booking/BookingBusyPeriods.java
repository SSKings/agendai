package com.sskings.agendai.booking;

import com.sskings.agendai.schedule.BusyPeriods;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Os agendamentos não cancelados ocupam o profissional (atendimento + preparo) nos horários livres da agenda. */
@Component
@RequiredArgsConstructor
class BookingBusyPeriods implements BusyPeriods {

    private final BookingRepository bookings;

    @Override
    public List<Busy> busy(UUID tenantId, UUID professionalId, Instant from, Instant to) {
        return bookings.occupying(tenantId, professionalId, BookingStatus.OCCUPYING, from.minus(BookingService.LONGEST), from, to).stream()
                .map(b -> new Busy(b.getStartsAt(), b.getBlockedUntil()))
                .toList();
    }
}
