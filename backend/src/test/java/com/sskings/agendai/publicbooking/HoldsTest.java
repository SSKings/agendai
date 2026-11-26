package com.sskings.agendai.publicbooking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HoldsTest {

    @Test
    @DisplayName("O que foi segurado vai e volta do Redis sem perder nada")
    void encodeDecode() {
        Holds.Hold h = new Holds.Hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDateTime.parse("2026-11-30T09:15"));
        assertThat(Holds.Hold.decode(h.encode())).isEqualTo(h);
    }
}
