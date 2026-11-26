package com.sskings.agendai.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Expira os pendentes da página pública que venceram, em todos os estabelecimentos (função do banco, V7). Seguro com
 * várias instâncias: é um UPDATE só, idempotente. Os horários livres já ignoram o pendente vencido mesmo antes disto.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PendingExpiry {

    private final BookingRepository bookings;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${agendai.pending.expiry-every:PT1M}", initialDelayString = "${agendai.pending.expiry-every:PT1M}")
    @Transactional
    public int run() {
        int n = bookings.expireAllPending(clock.instant());
        if (n > 0) log.info("Pendentes expirados: {}", n);
        return n;
    }
}
