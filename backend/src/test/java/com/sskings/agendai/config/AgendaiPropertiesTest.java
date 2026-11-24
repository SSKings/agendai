package com.sskings.agendai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgendaiPropertiesTest {

    @Test
    @DisplayName("CORS: origens sem espaços e sem vazias (CORS_ALLOWED_ORIGINS=\"a, ,b\"); ausente vira lista vazia")
    void corsOrigins() {
        assertThat(new AgendaiProperties.Cors(List.of(" https://a.app ", " ", "https://b.app")).allowedOrigins())
                .containsExactly("https://a.app", "https://b.app");
        assertThat(new AgendaiProperties.Cors(null).allowedOrigins()).isEmpty();
    }
}
