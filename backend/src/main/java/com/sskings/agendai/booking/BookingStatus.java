package com.sskings.agendai.booking;

import java.util.Set;

/**
 * Ciclo de vida do agendamento (ADR-009). Pela página pública nasce PENDING (vira CONFIRMED com o código do e-mail, ou
 * EXPIRED se o código vencer); pelo painel nasce CONFIRMED. CONFIRMED pode virar COMPLETED, NO_SHOW ou CANCELLED.
 * {@link #OCCUPYING}: o que ocupa o horário — o mesmo predicado da restrição de exclusão no banco (V4).
 */
public enum BookingStatus {
    PENDING, CONFIRMED, COMPLETED, NO_SHOW, CANCELLED, EXPIRED;

    public static final Set<BookingStatus> OCCUPYING = Set.of(PENDING, CONFIRMED, COMPLETED, NO_SHOW);
}
