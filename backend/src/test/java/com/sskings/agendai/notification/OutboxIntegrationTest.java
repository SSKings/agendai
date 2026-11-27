package com.sskings.agendai.notification;

import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.support.MailBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Outbox → RabbitMQ → worker (ADR-003): os testes-chave da fase 3. Segunda: 30/11. */
class OutboxIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    @Autowired MailBox mail;
    @Autowired RabbitTemplate rabbit;
    @Autowired OutboxPublisher publisher;
    @Autowired GenericContainer<?> rabbitmq;

    record Shop(String tenantId, String slug, String corte, String joao) {
        String pub() {
            return "/api/public/" + slug;
        }
    }

    Shop shop() throws Exception {
        Session ana = register("Ana Souza", email(), "Barbearia " + UUID.randomUUID().toString().substring(0, 8));
        String slug = body(call(get("/api/me"), ana.token(), null)).get("tenants").get(0).get("slug").asString();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 0, "priceCents", 4500, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        call(put(base + "/professionals/" + joao + "/working-hours"), ana.token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        return new Shop(ana.tenantId(), slug, corte, joao);
    }

    /** Pede o código pela página; devolve o token do pendente. */
    String pending(Shop s, String time, String email) throws Exception {
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T" + time));
        MockHttpServletResponse p = call(post(s.pub() + "/bookings"), null, Map.of("holdToken", body(h).get("token").asString(),
                "customer", Map.of("name", "Carla Dias", "phone", "11987654321", "email", email)));
        assertThat(p.getStatus()).as(p.getContentAsString()).isEqualTo(201);
        return body(p).get("token").asString();
    }

    long unpublished(String tenantId) throws Exception {
        return ownerCount("SELECT count(*) FROM outbox_events WHERE tenant_id = ?::uuid AND published_at IS NULL", tenantId);
    }

    @Test
    @DisplayName("RabbitMQ fora durante o agendamento: confirma do mesmo jeito; o aviso espera no outbox e sai quando ele volta")
    void brokerDown() throws Exception {
        Shop s = shop();
        String email = email();
        String token = pending(s, "09:00", email);
        String code = mail.lastCodeTo(email);
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(rabbitmq.getContainerId()).exec();
        try {
            MockHttpServletResponse c = call(post(s.pub() + "/bookings/confirm"), null, Map.of("token", token, "code", code));
            assertThat(c.getStatus()).as(c.getContentAsString()).isEqualTo(200);
            Thread.sleep(1500);   // o publicador tenta e não consegue
            assertThat(unpublished(s.tenantId())).isEqualTo(1);
            assertThat(mail.countTo(email, "Agendamento confirmado")).isZero();
        } finally {
            docker.unpauseContainerCmd(rabbitmq.getContainerId()).exec();
        }
        assertThat(mail.awaitCountTo(email, "Agendamento confirmado", 1)).isEqualTo(1);
        assertThat(unpublished(s.tenantId())).isZero();
        assertThat(ownerCount("SELECT count(*) FROM outbox_events WHERE tenant_id = ?::uuid AND payload IS NULL", s.tenantId()))
                .as("publicado: o conteúdo (e-mail, link pessoal) sai do banco").isEqualTo(1);
    }

    @Test
    @DisplayName("Código errado: a tentativa é gravada, mas nenhum aviso vai para o outbox")
    void rolledBackLeavesNothing() throws Exception {
        Shop s = shop();
        String email = email();
        String token = pending(s, "09:00", email);
        assertThat(call(post(s.pub() + "/bookings/confirm"), null, Map.of("token", token, "code", "000000")).getStatus()).isEqualTo(400);
        assertThat(ownerCount("SELECT count(*) FROM outbox_events WHERE tenant_id = ?::uuid", s.tenantId())).isZero();
    }

    Message message(String id, String to, String subject) {
        String body = "{\"to\":\"" + to + "\",\"subject\":\"" + subject + "\",\"text\":\"Olá\"}";
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).setContentType("application/json").setMessageId(id).build();
    }

    @Test
    @DisplayName("O mesmo evento entregue duas vezes: um e-mail só")
    void duplicateDelivery() throws Exception {
        String to = email();
        String id = UUID.randomUUID().toString();
        rabbit.send(Messaging.EXCHANGE, Messaging.EMAIL, message(id, to, "Duplicado"));
        rabbit.send(Messaging.EXCHANGE, Messaging.EMAIL, message(id, to, "Duplicado"));
        assertThat(mail.awaitCountTo(to, "Duplicado", 1)).isEqualTo(1);
        Thread.sleep(1000);
        assertThat(mail.countTo(to, "Duplicado")).isEqualTo(1);
        // outro evento para o mesmo endereço: sai
        rabbit.send(Messaging.EXCHANGE, Messaging.EMAIL, message(UUID.randomUUID().toString(), to, "Duplicado"));
        assertThat(mail.awaitCountTo(to, "Duplicado", 2)).isEqualTo(2);
    }

    @Test
    @DisplayName("Mensagem malformada (sem id, JSON quebrado) vai direto para a fila de falhas")
    void malformedToDeadLetters() throws Exception {
        rabbit.send(Messaging.EXCHANGE, Messaging.EMAIL, MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).build());
        Message dead = rabbit.receive(Messaging.EMAIL_DLQ, 10_000);
        assertThat(dead).isNotNull();
        rabbit.send(Messaging.EXCHANGE, Messaging.EMAIL, MessageBuilder.withBody("nada".getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString()).build());
        assertThat(rabbit.receive(Messaging.EMAIL_DLQ, 10_000)).isNotNull();
    }

    @Test
    @DisplayName("Várias instâncias: evento que outra está publicando (travado) é pulado, não publicado duas vezes")
    void skipLocked() throws Exception {
        Shop s = shop();
        String to = email();
        String lockedId;
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "agendai_owner", "owner-test")) {
            // a "outra instância" trava o evento logo depois de ele existir; o publicador de fundo pode chegar antes —
            // então tenta de novo com outro evento até a trava ganhar (quase sempre na primeira)
            String id = null;
            for (int attempt = 0; attempt < 20 && id == null; attempt++) {
                String candidate = UUID.randomUUID().toString();
                c.setAutoCommit(true);
                try (var ins = c.prepareStatement("""
                        INSERT INTO outbox_events (id, tenant_id, type, payload, created_at)
                        VALUES (?::uuid, ?::uuid, 'notification.email.v1', cast(? as jsonb), now())""")) {
                    ins.setString(1, candidate);
                    ins.setString(2, s.tenantId());
                    ins.setString(3, "{\"to\":\"" + to + "\",\"subject\":\"Travado " + candidate + "\",\"text\":\"Olá\"}");
                    ins.executeUpdate();
                }
                c.setAutoCommit(false);
                try (var lock = c.prepareStatement(
                        "SELECT id FROM outbox_events WHERE id = ?::uuid AND published_at IS NULL FOR UPDATE SKIP LOCKED")) {
                    lock.setString(1, candidate);
                    try (var rs = lock.executeQuery()) {
                        if (rs.next()) id = candidate;
                    }
                }
                if (id == null) c.rollback();
            }
            assertThat(id).as("a trava ganhou do publicador de fundo").isNotNull();
            // um evento livre ao lado: quem pula (SKIP LOCKED) publica este na hora; quem esperasse a trava, não
            String free = UUID.randomUUID().toString();
            try (var other = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "agendai_owner", "owner-test");
                 var ins = other.prepareStatement("""
                         INSERT INTO outbox_events (id, tenant_id, type, payload, created_at)
                         VALUES (?::uuid, ?::uuid, 'notification.email.v1', cast(? as jsonb), now())""")) {
                ins.setString(1, free);         // por outra conexão: a trava do primeiro continua com esta
                ins.setString(2, s.tenantId());
                ins.setString(3, "{\"to\":\"" + to + "\",\"subject\":\"Livre " + free + "\",\"text\":\"Olá\"}");
                ins.executeUpdate();
            }
            long started = System.nanoTime();
            publisher.run();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).as("não esperou a trava").isLessThan(java.time.Duration.ofSeconds(2));
            assertThat(mail.awaitCountTo(to, "Livre " + free, 1)).as("o livre saiu").isEqualTo(1);
            assertThat(ownerCount("SELECT count(*) FROM outbox_events WHERE id = ?::uuid AND published_at IS NULL", id)).isEqualTo(1);
            assertThat(mail.countTo(to, "Travado " + id)).isZero();
            lockedId = id;
            c.rollback();                   // a outra instância caiu sem publicar: o evento volta para a fila
        }
        // o id do evento travado é guardado fora do try: as asserções finais olham só ele
        assertThat(mail.awaitCountTo(to, "Travado " + lockedId, 1)).isEqualTo(1);
        Thread.sleep(1000);
        assertThat(mail.countTo(to, "Travado " + lockedId)).as("sai uma vez").isEqualTo(1);
    }

    @Test
    @DisplayName("Fila de espera: a mensagem volta à fila dos e-mails depois do prazo e é entregue")
    void waitQueueReturns() throws Exception {
        String to = email();
        rabbit.send("", Messaging.EMAIL_WAIT, message(UUID.randomUUID().toString(), to, "Depois da espera"));
        assertThat(mail.awaitCountTo(to, "Depois da espera", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("Evento sem fila (tipo sem consumidor): o broker confirma, e a mensagem fica guardada na fila dos não roteados")
    void unroutedKept() throws Exception {
        String id = UUID.randomUUID().toString();
        rabbit.send(Messaging.EXCHANGE, "nada.consome.v1", message(id, email(), "Sem fila"));
        Message kept = rabbit.receive(Messaging.UNROUTED, 10_000);
        assertThat(kept).isNotNull();
        assertThat(kept.getMessageProperties().getMessageId()).isEqualTo(id);
    }
}

