package com.sskings.agendai.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Consome os e-mails do outbox (ADR-003).
 * <ul>
 *   <li>Idempotente pelo id do evento: {@code processed_events} na mesma transação do envio — entregue duas vezes, manda
 *   uma; se o envio falha, a transação desfaz e o evento não conta como processado.</li>
 *   <li>Falha (provedor fora, banco fora): a mensagem vai para a fila de espera e volta depois de
 *   {@code agendai.worker.retry-delay}, até {@code agendai.worker.max-attempts} tentativas (padrão: 1 min × 180 = 3 h)
 *   — uma queda longa do provedor atrasa os avisos, não os perde. Sem dormir no thread de consumo.</li>
 *   <li>Esgotou, ou a mensagem não tem conserto (sem id, JSON quebrado, campo faltando): fila de falhas.</li>
 * </ul>
 * Encaminhar exige a confirmação do broker; se ela não vem, a exceção devolve a mensagem à fila (nada se perde).
 */
@Slf4j
@Component
public class EmailWorker {

    static final String CONSUMER = "email";
    static final String ATTEMPT = "x-agendai-attempt";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @PersistenceContext
    private EntityManager em;

    private final Mailer mailer;
    private final TransactionTemplate tx;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final int maxAttempts;

    public EmailWorker(Mailer mailer, TransactionTemplate tx, RabbitTemplate rabbit, Clock clock,
                       @Value("${agendai.worker.max-attempts:180}") int maxAttempts) {
        this.mailer = mailer;
        this.tx = tx;
        this.rabbit = rabbit;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    record Email(UUID id, String to, String subject, String text) {
        static Email of(Message m) {
            JsonNode n = JSON.readTree(m.getBody());
            return new Email(UUID.fromString(m.getMessageProperties().getMessageId()), text(n, "to"), text(n, "subject"), text(n, "text"));
        }

        private static String text(JsonNode n, String field) {
            JsonNode v = n.get(field);
            if (v == null || !v.isString() || v.asString().isBlank()) throw new IllegalArgumentException("Campo ausente: " + field);
            return v.asString();
        }
    }

    @RabbitListener(queues = Messaging.EMAIL_QUEUE, autoStartup = "${agendai.worker.enabled:true}")
    public void on(Message message) {
        Email email;
        try {
            email = Email.of(message);
        } catch (RuntimeException e) {
            log.warn("Mensagem malformada na fila de e-mails: para a fila de falhas");
            forward(message, Messaging.EMAIL_DLQ, attemptOf(message));
            return;
        }
        try {
            tx.executeWithoutResult(s -> deliver(email));
        } catch (RuntimeException e) {
            int attempt = attemptOf(message) + 1;
            if (attempt >= maxAttempts) {
                log.warn("Evento {}: {} tentativas sem sucesso; para a fila de falhas", email.id(), attempt);
                forward(message, Messaging.EMAIL_DLQ, attempt);
            } else {
                log.info("Evento {}: tentativa {} falhou ({}); de novo depois da espera", email.id(), attempt, e.getClass().getSimpleName());
                forward(message, Messaging.EMAIL_WAIT, attempt);
            }
        }
    }

    private void deliver(Email email) {
        int fresh = em.createNativeQuery("""
                        INSERT INTO processed_events (consumer, event_id, processed_at) VALUES (:consumer, :id, :now)
                        ON CONFLICT DO NOTHING""")
                .setParameter("consumer", CONSUMER).setParameter("id", email.id()).setParameter("now", clock.instant())
                .executeUpdate();
        if (fresh == 0) {
            log.info("Evento {} já processado: ignorado", email.id());
            return;
        }
        mailer.send(email.to(), email.subject(), email.text());
    }

    static int attemptOf(Message m) {
        Object a = m.getMessageProperties().getHeader(ATTEMPT);
        return a instanceof Number n ? n.intValue() : 0;
    }

    /** Copia para a fila (pelo exchange padrão) com o número da tentativa, e espera o broker confirmar. */
    private void forward(Message m, String queue, int attempt) {
        Message copy = MessageBuilder.fromMessage(m).setHeader(ATTEMPT, attempt).build();
        rabbit.invoke(ops -> {
            ops.send("", queue, copy);
            ops.waitForConfirmsOrDie(OutboxPublisher.CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
