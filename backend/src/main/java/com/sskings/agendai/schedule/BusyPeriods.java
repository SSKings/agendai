package com.sskings.agendai.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta da agenda para quem ocupa o tempo de um profissional além dos bloqueios (os agendamentos, módulo
 * {@code booking}). A dependência vai de {@code booking} para {@code schedule}, nunca o contrário (ADR-008).
 * Chamada dentro da transação dos horários livres, que já passou pelo guard.
 */
public interface BusyPeriods {

    /** Períodos [start, end) ocupados do profissional que encostam em [from, to). */
    List<Busy> busy(UUID tenantId, UUID professionalId, Instant from, Instant to);

    record Busy(Instant start, Instant end) {}
}
