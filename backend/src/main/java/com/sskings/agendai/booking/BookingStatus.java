package com.sskings.agendai.booking;

import java.util.Set;

/**
 * Ciclo de vida do agendamento (ADR-009). CONFIRMED pode virar COMPLETED, NO_SHOW ou CANCELLED, que são finais.
 * {@link #OCCUPYING}: o que ocupa o horário do profissional — o mesmo predicado da restrição de exclusão no banco
 * (V4), que já inclui o PENDING da fase 2; atendido e falta continuam ocupando (histórico do horário).
 */
public enum BookingStatus {
    CONFIRMED, COMPLETED, NO_SHOW, CANCELLED;

    public static final Set<BookingStatus> OCCUPYING = Set.of(CONFIRMED, COMPLETED, NO_SHOW);
}
