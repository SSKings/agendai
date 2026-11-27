package com.sskings.agendai.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Uma passada do publicador, sem broker nem banco: o que sai, em que ordem, e o que devolve. */
class OutboxPublisherTest {

    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final RabbitOperations ops = mock(RabbitOperations.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    final EntityManager em = mock(EntityManager.class);
    final Query claim = mock(Query.class, RETURNS_SELF);
    final Query published = mock(Query.class, RETURNS_SELF);
    final Instant now = Instant.parse("2026-11-30T12:00:00Z");
    final OutboxPublisher publisher = new OutboxPublisher(rabbit, tx, Clock.fixed(now, ZoneOffset.UTC));

    OutboxPublisherTest() {
        ReflectionTestUtils.setField(publisher, "em", em);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        doAnswer(i -> ((RabbitOperations.OperationsCallback<?>) i.getArgument(0)).doInRabbit(ops)).when(rabbit).invoke(any());
        when(em.createNativeQuery(anyString())).thenAnswer(i -> ((String) i.getArgument(0)).contains("outbox_claim") ? claim : published);
    }

    @Test
    @DisplayName("Nada pendente: devolve 0 sem tocar no broker nem marcar nada")
    void empty() {
        when(claim.getResultList()).thenReturn(List.of());
        assertThat(publisher.run()).isZero();
        verifyNoInteractions(rabbit);
        verify(published, never()).getSingleResult();
    }

    @Test
    @DisplayName("Pendentes: publica cada um persistente com o id do evento, espera a confirmação, marca e devolve quantos")
    void publishes() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(claim.getResultList()).thenReturn(List.of(
                new Object[]{a, Messaging.EMAIL, "{\"n\":1}"}, new Object[]{b, Messaging.EMAIL, "{\"n\":2}"}));

        assertThat(publisher.run()).isEqualTo(2);

        verify(claim).setParameter("max", OutboxPublisher.BATCH);
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(ops, published);
        order.verify(ops, org.mockito.Mockito.times(2)).send(eq(Messaging.EXCHANGE), eq(Messaging.EMAIL), sent.capture());
        order.verify(ops).waitForConfirmsOrDie(OutboxPublisher.CONFIRM_TIMEOUT_MS);
        order.verify(published).getSingleResult();
        Message first = sent.getAllValues().get(0);
        assertThat(first.getMessageProperties().getMessageId()).isEqualTo(a.toString());
        assertThat(first.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(first.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}");
        verify(published).setParameter("ids", new UUID[]{a, b});
        verify(published).setParameter("now", now);
    }
}
