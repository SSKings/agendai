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
    public static final String RABBITMQ_IMAGE =
            "rabbitmq:4.1-alpine@sha256:146b4c1ceed64aa5ac1931f5bf1aac1b10ad0ac8193773410e0c1d15050f8ec7";
    static final String RABBITMQ_PASSWORD = "rabbit-test-password";
    static final String MAILPIT_IMAGE =
            "axllent/mailpit:v1.27@sha256:e22dce5b36f93c77082e204a3942fb6b283b7896e057458400a4c88344c3df68";

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

    /** Broker dos eventos (outbox → worker), com usuário próprio como na produção (o "guest" só vale em localhost). */
    @Bean(initMethod = "start", destroyMethod = "stop")
    GenericContainer<?> rabbitmq() {
        return new GenericContainer<>(DockerImageName.parse(RABBITMQ_IMAGE))
                .withExposedPorts(5672)
                .withEnv("RABBITMQ_DEFAULT_USER", "agendai")
                .withEnv("RABBITMQ_DEFAULT_PASS", RABBITMQ_PASSWORD)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Server startup complete.*", 1)
                        .withStartupTimeout(java.time.Duration.ofMinutes(2)));
    }

    /** SMTP de teste: guarda os e-mails e os mostra por uma API HTTP (MailBox lê o código de confirmação). */
    @Bean(initMethod = "start", destroyMethod = "stop")
    GenericContainer<?> mailpit() {
        return new GenericContainer<>(DockerImageName.parse(MAILPIT_IMAGE)).withExposedPorts(1025, 8025);
    }

    @Bean
    MailBox mailBox(GenericContainer<?> mailpit) {
        return new MailBox("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025));
    }

    @Bean
    DynamicPropertyRegistrar containers(PostgreSQLContainer postgres, GenericContainer<?> redis, GenericContainer<?> mailpit,
                                        GenericContainer<?> rabbitmq) {
        return r -> {
            r.add("spring.datasource.url", postgres::getJdbcUrl);
            r.add("spring.datasource.username", () -> "agendai_app");
            r.add("spring.datasource.password", () -> "app-test");
            r.add("spring.flyway.user", () -> "agendai_owner");
            r.add("spring.flyway.password", () -> "owner-test");
            r.add("spring.data.redis.host", redis::getHost);
            r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
            r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
            r.add("spring.rabbitmq.host", rabbitmq::getHost);
            r.add("spring.rabbitmq.port", () -> rabbitmq.getMappedPort(5672));
            r.add("spring.rabbitmq.username", () -> "agendai");
            r.add("spring.rabbitmq.password", () -> RABBITMQ_PASSWORD);
            r.add("spring.mail.host", mailpit::getHost);
            r.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
            r.add("spring.mail.properties.mail.smtp.auth", () -> "false");
            r.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
        };
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
