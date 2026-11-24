package com.sskings.agendai.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio dos testes: começa num instante FIXO (nada depende da hora em que o teste roda) e só anda quando o teste
 * manda (bloqueio, expiração de token/convite).
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public static final Instant START = Instant.parse("2026-11-24T09:00:00Z");

    public MutableClock() {
        this(new AtomicReference<>(START), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    /** Fixa o instante (ex.: "hoje é 10/11/2026" nos testes de previsão). Chame ANTES de cadastrar: o token nasce nele. */
    public void set(Instant instant) {
        now.set(instant);
    }

    public void reset() {
        now.set(START);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(now, z); }   // mesmo instante, outro fuso
    @Override public Instant instant() { return now.get(); }
}
