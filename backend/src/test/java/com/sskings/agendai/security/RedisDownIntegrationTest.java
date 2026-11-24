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
}
