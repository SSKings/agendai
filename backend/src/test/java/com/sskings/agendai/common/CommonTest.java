package com.sskings.agendai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonTest {

    @Test
    @DisplayName("Texto: espaços juntos e nas pontas saem; nulo continua nulo")
    void texts() {
        assertThat(Texts.clean("  Ana   Souza  ")).isEqualTo("Ana Souza");
        assertThat(Texts.clean(null)).isNull();
    }

    @Test
    @DisplayName("Comparação sem acento e sem maiúscula; nulo vira vazio")
    void folding() {
        assertThat(Folding.fold("  São   JOÃO ")).isEqualTo("sao joao");
        assertThat(Folding.fold(null)).isEmpty();
    }

    @Test
    @DisplayName("Tokens: 256 bits em base64url (43 caracteres), formato conferido, SHA-256 hexadecimal; algoritmo ausente dá erro claro")
    void tokens() {
        String t = Tokens.random();
        assertThat(Tokens.wellFormed(t)).isTrue();
        assertThat(Tokens.random()).isNotEqualTo(t);
        assertThat(Tokens.wellFormed(null)).isFalse();
        assertThat(Tokens.wellFormed(t + "x")).isFalse();
        assertThat(Tokens.wellFormed("+".repeat(43))).isFalse();
        assertThat(Tokens.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThatThrownBy(() -> Tokens.digest("NAO-EXISTE", "abc")).isInstanceOf(IllegalStateException.class)
                .hasMessage("NAO-EXISTE unavailable");
    }
}
