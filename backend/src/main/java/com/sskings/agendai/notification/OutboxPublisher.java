package com.sskings.agendai.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Leva o outbox ao RabbitMQ (ADR-003). Numa transação: pega até {@link #BATCH} eventos (SKIP LOCKED — outra instância
 * pega outros), publica cada um persistente, espera a confirmação do broker e só então marca como publicados. Broker
 * fora ou sem confirmar: a transação desfaz, os eventos continuam pendentes e saem na próxima passada. Pode publicar o
 * mesmo evento duas vezes (confirmou e a transação caiu antes do commit): o worker é idempotente pelo id.
 */
@Slf4j
@ConditionalOnBooleanProperty(name = "agendai.outbox.enabled", matchIfMissing = true)   // papel api
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    static final int BATCH = 100;
    static final long CONFIRM_TIMEOUT_MS = 5000;

    @PersistenceContext
    private EntityManager em;

    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** Falhando: um aviso no log quando começa e quando volta, não um por segundo. */
    private volatile boolean failing;

    @Scheduled(fixedDelayString = "${agendai.outbox.publish-every:PT1S}")
    public int run() {
        try {
            int n = tx.execute(s -> publishBatch());
            if (failing) log.info("Outbox voltou a publicar");
            failing = false;
            return n;
        } catch (RuntimeException e) {
            if (!failing) log.warn("Outbox sem publicar ({}): os eventos esperam e saem quando o broker voltar", e.getClass().getSimpleName());
            failing = true;
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private int publishBatch() {
        List<Object[]> rows = em.createNativeQuery("SELECT id, type, cast(payload as text) FROM outbox_claim(:max)")
                .setParameter("max", BATCH).getResultList();
        if (rows.isEmpty()) return 0;
        rabbit.invoke(ops -> {
            for (Object[] r : rows) {
                Message m = MessageBuilder.withBody(((String) r[2]).getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setMessageId(r[0].toString())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                ops.send(Messaging.EXCHANGE, (String) r[1], m);
            }
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
        UUID[] ids = rows.stream().map(r -> (UUID) r[0]).toArray(UUID[]::new);
        em.createNativeQuery("SELECT outbox_published(:ids, :now)")
                .setParameter("ids", ids).setParameter("now", clock.instant()).getSingleResult();
        return rows.size();
    }
}
