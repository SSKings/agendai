package com.sskings.agendai.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL e Redis de verdade (as mesmas imagens da produção, fixadas por digest) e relógio controlável.
 * O PostgreSQL tem os mesmos três usuários da produção: o Flyway migra com o dono das tabelas e a API conecta com o
 * usuário só de dados — sujeito ao RLS. Um contexto para todos os testes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String POSTGRES_IMAGE =
            "postgres:16-alpine@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d";
    static final String REDIS_IMAGE =
            "redis:7.4-alpine@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe";
    static final String REDIS_PASSWORD = "redis-test-password";

    @Bean(initMethod = "start", destroyMethod = "stop")
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("agendai")
                .withInitScript("db/test-roles.sql");
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);
    }

    @Bean
    DynamicPropertyRegistrar containers(PostgreSQLContainer postgres, GenericContainer<?> redis) {
        return r -> {
            r.add("spring.datasource.url", postgres::getJdbcUrl);
            r.add("spring.datasource.username", () -> "agendai_app");
            r.add("spring.datasource.password", () -> "app-test");
            r.add("spring.flyway.user", () -> "agendai_owner");
            r.add("spring.flyway.password", () -> "owner-test");
            r.add("spring.data.redis.host", redis::getHost);
            r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
            r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        };
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
