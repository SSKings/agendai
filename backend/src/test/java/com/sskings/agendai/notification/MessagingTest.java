package com.sskings.agendai.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A topologia declarada: o teste de integração usa um broker que dura o JVM inteiro (filas já declaradas continuam lá),
 * então é aqui que os argumentos das filas e os bindings ficam garantidos.
 */
class MessagingTest {

    final Messaging m = new Messaging();

    @Test
    @DisplayName("Eventos: exchange topic durável, com o alternativo que guarda o que não tem fila")
    void events() {
        TopicExchange events = m.events();
        assertThat(events.getName()).isEqualTo(Messaging.EXCHANGE);
        assertThat(events.isDurable()).isTrue();
        assertThat(events.getArguments()).containsEntry("alternate-exchange", Messaging.UNROUTED_EXCHANGE);

        FanoutExchange unroutedExchange = m.unroutedExchange();
        assertThat(unroutedExchange.getName()).isEqualTo(Messaging.UNROUTED_EXCHANGE);
        assertThat(unroutedExchange.isDurable()).isTrue();
        Queue unrouted = m.unrouted();
        assertThat(unrouted.getName()).isEqualTo(Messaging.UNROUTED);
        parked(unrouted);
        Binding b = m.unroutedBinding(unrouted, unroutedExchange);
        assertThat(b.getDestination()).isEqualTo(Messaging.UNROUTED);
        assertThat(b.getExchange()).isEqualTo(Messaging.UNROUTED_EXCHANGE);
    }

    @Test
    @DisplayName("E-mails: fila quorum ligada pelo tipo do evento; espera devolve pelo exchange; falhas guardadas 7 dias")
    void email() {
        Queue q = m.emailQueue();
        assertThat(q.getName()).isEqualTo(Messaging.EMAIL_QUEUE);
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments()).containsEntry("x-queue-type", "quorum");
        Binding b = m.emailBinding(q, m.events());
        assertThat(b.getDestination()).isEqualTo(Messaging.EMAIL_QUEUE);
        assertThat(b.getExchange()).isEqualTo(Messaging.EXCHANGE);
        assertThat(b.getRoutingKey()).isEqualTo(Messaging.EMAIL);

        Queue wait = m.emailWait(Duration.ofSeconds(30));
        assertThat(wait.getName()).isEqualTo(Messaging.EMAIL_WAIT);
        assertThat(wait.isDurable()).isTrue();
        assertThat(wait.getArguments()).containsAllEntriesOf(Map.of(
                "x-queue-type", "quorum",
                "x-message-ttl", 30_000,
                "x-dead-letter-exchange", Messaging.EXCHANGE,
                "x-dead-letter-routing-key", Messaging.EMAIL,
                "x-dead-letter-strategy", "at-least-once",
                "x-overflow", "reject-publish"));

        Queue dlq = m.emailDeadLetters();
        assertThat(dlq.getName()).isEqualTo(Messaging.EMAIL_DLQ);
        parked(dlq);
    }

    static void parked(Queue q) {
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments()).containsAllEntriesOf(Map.of(
                "x-queue-type", "quorum",
                "x-message-ttl", (int) Duration.ofDays(7).toMillis(),
                "x-max-length", (long) Messaging.PARKED_MAX));
    }

    @Test
    @DisplayName("O publicador usa conexão própria (alarme de memória do broker não trava os consumidores)")
    void publisherConnection() {
        RabbitTemplate t = mock(RabbitTemplate.class);
        m.publisherConnection().customize(t);
        verify(t).setUsePublisherConnection(true);
    }
}
