package com.sskings.agendai.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Cálculo dos horários livres, sem banco nem fuso: só instantes. */
final class Slots {

    /** De quanto em quanto tempo um horário pode começar, a partir do início de cada faixa de trabalho. */
    static final Duration STEP = Duration.ofMinutes(15);

    private Slots() {}

    /** Intervalo semiaberto [start, end): um termina exatamente quando o outro começa sem se sobreporem. */
    record Span(Instant start, Instant end) {
        boolean overlaps(Span o) {
            return start.isBefore(o.end) && o.start.isBefore(end);
        }
    }

    /**
     * Inícios em que o bloco inteiro (atendimento + preparo) cabe numa faixa de trabalho, não encosta em nenhum
     * período ocupado e não começa antes de {@code notBefore}.
     */
    static List<Instant> free(List<Span> working, List<Span> busy, Duration block, Instant notBefore) {
        List<Instant> out = new ArrayList<>();
        for (Span w : working) {
            for (Instant t = w.start(); !t.plus(block).isAfter(w.end()); t = t.plus(STEP)) {
                Span candidate = new Span(t, t.plus(block));
                if (!t.isBefore(notBefore) && busy.stream().noneMatch(candidate::overlaps)) out.add(t);
            }
        }
        return out;
    }
}
