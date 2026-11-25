package com.sskings.agendai.security;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Limite por IP no Redis (ADR-007): vale para todas as rotas sem login e entre instâncias. */
class RateLimitIntegrationTest extends IntegrationTest {

    private int login(String ip) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("email", "x@y.com", "password", "Errada12345"), ip).getStatus();
    }

    @Test
    @DisplayName("Login, cadastro e refresh dividem 20 por minuto por IP; a 21ª recebe 429 com Retry-After; outro IP não é afetado")
    void limitPerIp() throws Exception {
        clock.set(Instant.parse("2026-11-24T12:00:10Z"));
        String ip = ip();
        for (int i = 0; i < 18; i++) assertThat(login(ip)).isEqualTo(401);
        assertThat(call(post("/api/auth/register"), null, Map.of(), ip).getStatus()).isEqualTo(400);
        assertThat(call(post("/api/auth/refresh").header("X-Requested-With", "agendai"), null, null, ip).getStatus()).isEqualTo(401);
        MockHttpServletResponse blocked = call(post("/api/auth/login"), null, Map.of("email", "x@y.com", "password", "Errada12345"), ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("50");
        assertThat(blocked.getContentAsString()).isEqualTo("{\"status\":429,\"message\":\"Too many requests\"}");
        assertThat(login(ip())).as("outro IP").isEqualTo(401);
        clock.advance(Duration.ofSeconds(60));
        assertThat(login(ip)).as("minuto seguinte").isEqualTo(401);
    }

    @Test
    @DisplayName("IPv6 conta pelo bloco /64: trocar de endereço dentro do bloco não renova o limite")
    void ipv6Block() throws Exception {
        clock.set(Instant.parse("2026-11-24T13:00:00Z"));
        for (int i = 0; i < 20; i++) login("2001:db8:88:1::" + Integer.toHexString(i + 1));
        assertThat(login("2001:db8:88:1:ffff:ffff:ffff:ffff")).isEqualTo(429);
        assertThat(login("2001:db8:88:2::1")).as("outro /64").isEqualTo(401);
    }
}
