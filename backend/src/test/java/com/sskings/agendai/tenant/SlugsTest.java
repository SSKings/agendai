package com.sskings.agendai.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @Test
    @DisplayName("Link a partir do nome: sem acento, minúsculo, hífens entre palavras, até 50 caracteres; vazio vira 'negocio'")
    void slugs() {
        assertThat(Slugs.of("Barbearia São João")).isEqualTo("barbearia-sao-joao");
        assertThat(Slugs.of("  --Estúdio   & Cia!!  ")).isEqualTo("estudio-cia");
        assertThat(Slugs.of("ação")).isEqualTo("acao");
        assertThat(Slugs.of("!!!")).isEqualTo("negocio");
        String longName = "a".repeat(49) + " bcd";
        assertThat(Slugs.of(longName)).as("corta sem deixar hífen no fim").isEqualTo("a".repeat(49));
        assertThat(Slugs.of("x".repeat(80))).hasSize(Slugs.MAX);
    }
}
