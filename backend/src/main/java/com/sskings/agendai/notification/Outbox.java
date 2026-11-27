package com.sskings.agendai.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Grava o aviso na transação de quem o causou (ADR-003): se ela desfaz, o aviso some junto; se ela grava, o aviso sai
 * — mesmo com o RabbitMQ ou o provedor de e-mail fora do ar naquele momento. Só dentro de uma transação que já entrou
 * no estabelecimento (o RLS confere o {@code tenant_id}).
 */
@Component
@RequiredArgsConstructor
public class Outbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @PersistenceContext
    private EntityManager em;

    private final Clock clock;

    /** Um e-mail de texto puro. Devolve o id do evento (é o id da mensagem no RabbitMQ). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID email(UUID tenantId, String to, String subject, String text) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
                        INSERT INTO outbox_events (id, tenant_id, type, payload, created_at)
                        VALUES (:id, :tenant, :type, cast(:payload as jsonb), :now)""")
                .setParameter("id", id)
                .setParameter("tenant", tenantId)
                .setParameter("type", Messaging.EMAIL)
                .setParameter("payload", JSON.writeValueAsString(Map.of("to", to, "subject", subject, "text", text)))
                .setParameter("now", clock.instant())
                .executeUpdate();
        return id;
    }
}
