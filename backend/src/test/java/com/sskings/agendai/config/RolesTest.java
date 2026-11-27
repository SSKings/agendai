package com.sskings.agendai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Os papéis de produção (application-api.yml, application-worker.yml): cada processo sobe só os componentes de
 * fundo do seu papel. Sem papel (dev, testes), um processo faz tudo.
 */
class RolesTest {

    static final List<String> BACKGROUND = List.of("outboxPublisher", "emailWorker", "pendingExpiry", "customerRetention", "migrateOnly");
    static final String RABBIT = "org.springframework.boot.rabbitmq.autoconfigure.RabbitAutoConfiguration";

    /** Só os componentes de fundo, com as dependências de mentira: o que importa aqui é se o bean existe. */
    @Configuration
    @ComponentScan(basePackages = "com.sskings.agendai", useDefaultFilters = false, includeFilters = @ComponentScan.Filter(
            type = FilterType.REGEX, pattern = ".*\\.(OutboxPublisher|EmailWorker|PendingExpiry|CustomerRetention|MigrateOnly)"))
    static class Background {}

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Background.class)
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(jakarta.persistence.EntityManagerFactory.class, () -> mock(jakarta.persistence.EntityManagerFactory.class))
            .withBean(com.sskings.agendai.notification.Mailer.class, () -> mock(com.sskings.agendai.notification.Mailer.class))
            .withInitializer(ctx -> {
                // repositórios do pacote booking (não públicos): pelo nome
                for (String repo : List.of("BookingRepository", "CustomerRepository")) {
                    try {
                        ctx.getBeanFactory().registerSingleton(repo, mock(Class.forName("com.sskings.agendai.booking." + repo)));
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

    static List<String> background(AssertableApplicationContext ctx) {
        return BACKGROUND.stream().filter(ctx::containsBean).toList();
    }

    static String prop(AssertableApplicationContext ctx, String key) {
        return ctx.getEnvironment().getProperty(key);
    }

    @Test
    @DisplayName("prod,api: só o HTTP — sem RabbitMQ (nem a autoconfiguração), sem jobs, sem migrar")
    void api() {
        runner.withPropertyValues("spring.profiles.active=prod,api").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(background(ctx)).isEmpty();
            assertThat(prop(ctx, "spring.autoconfigure.exclude")).isEqualTo(RABBIT);
            assertThat(prop(ctx, "spring.flyway.enabled")).isEqualTo("false");
            assertThat(prop(ctx, "server.port")).isEqualTo("8080");
            assertThat(prop(ctx, "management.server.port")).isNull();
            assertThat(prop(ctx, "agendai.jwt.required")).as("a API exige o segredo").isNull();
        });
    }

    @Test
    @DisplayName("prod,worker: outbox, consumo e jobs; sem porta da API, health só no localhost; sem migrar, sem JWT")
    void worker() {
        runner.withPropertyValues("spring.profiles.active=prod,worker").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(background(ctx)).containsExactly("outboxPublisher", "emailWorker", "pendingExpiry", "customerRetention");
            assertThat(prop(ctx, "server.port")).isEqualTo("-1");
            assertThat(prop(ctx, "management.server.port")).isEqualTo("8081");
            assertThat(prop(ctx, "management.server.address")).isEqualTo("127.0.0.1");
            assertThat(prop(ctx, "management.health.redis.enabled")).isEqualTo("false");
            assertThat(prop(ctx, "management.health.mail.enabled")).isEqualTo("false");
            assertThat(prop(ctx, "management.health.rabbit.enabled")).isEqualTo("true");
            assertThat(prop(ctx, "spring.flyway.enabled")).isEqualTo("false");
            assertThat(prop(ctx, "spring.autoconfigure.exclude")).as("o worker fala com o RabbitMQ").isNull();
            assertThat(prop(ctx, "agendai.jwt.required")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("prod,migrate: só o Flyway e sai — sem HTTP, sem RabbitMQ, sem componentes de fundo")
    void migrate() {
        runner.withPropertyValues("spring.profiles.active=prod,migrate").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(background(ctx)).containsExactly("migrateOnly");
            assertThat(prop(ctx, "server.port")).as("nada escuta").isEqualTo("-1");
            assertThat(prop(ctx, "spring.autoconfigure.exclude")).isEqualTo(RABBIT);
            assertThat(prop(ctx, "spring.flyway.enabled")).as("é ele quem migra").isEqualTo("true");
            assertThat(prop(ctx, "agendai.jwt.required")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("Sem papel (dev, testes): um processo faz tudo, menos sair depois de migrar")
    void allInOne() {
        runner.withPropertyValues("spring.profiles.active=test").run(ctx ->
                assertThat(background(ctx)).containsExactly("outboxPublisher", "emailWorker", "pendingExpiry", "customerRetention"));
    }
}
