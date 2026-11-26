package com.sskings.agendai.security;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ADR-007: com o Redis fora, a API continua respondendo (sem o limite por IP) e o bloqueio por conta segue valendo. */
class RedisDownIntegrationTest extends IntegrationTest {

    @Autowired GenericContainer<?> redis;

    @Test
    @DisplayName("Redis parado: login responde rápido (timeout curto), o limite por IP é pulado e o bloqueio por conta continua")
    void redisPaused() throws Exception {
        Session s = register();
        String ip = ip();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                assertThat(call(post("/api/auth/login"), null, Map.of("email", s.email(), "password", "Errada12345"), ip)
                        .getStatus()).isEqualTo(401);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("5 logins com o Redis parado (timeout de 200 ms cada)").isLessThan(5_000);
            assertThat(call(post("/api/auth/login"), null, Map.of("email", s.email(), "password", PASSWORD), ip).getStatus())
                    .as("bloqueio por conta vem do banco").isEqualTo(429);
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    @Test
    @DisplayName("Redis parado: a página pública não reserva (503 'tente de novo'), mas o painel continua agendando")
    void holdsNeedRedis() throws Exception {
        Session ana = register("Ana", email(), "Loja Sem Redis " + java.util.UUID.randomUUID().toString().substring(0, 6));
        String slug = body(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me"), ana.token(), null))
                .get("tenants").get(0).get("slug").asString();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 0, "priceCents", 0, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", java.util.List.of(corte)))).get("id").asString();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/professionals/" + joao + "/working-hours"),
                ana.token(), Map.of("intervals", java.util.List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        try {
            var held = call(post("/api/public/" + slug + "/holds"), null, Map.of("serviceId", corte, "professionalId", joao,
                    "start", "2026-11-30T09:00"));
            assertThat(held.getStatus()).as(held.getContentAsString()).isEqualTo(503);
            assertThat(body(held).get("message").asString()).isEqualTo("Busy, try again");
            var taken = call(post("/api/public/" + slug + "/bookings"), null, Map.of("holdToken", "x".repeat(43),
                    "customer", Map.of("name", "Carla", "phone", "11987654321", "email", "c@x.com")));
            assertThat(taken.getStatus()).isEqualTo(503);
            assertThat(call(post(base + "/bookings"), ana.token(), Map.of("professionalId", joao, "serviceId", corte,
                    "start", "2026-11-30T09:00", "customer", Map.of("name", "Carla", "phone", "11987654321"))).getStatus())
                    .as("o painel não depende do Redis").isEqualTo(201);
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }
}
