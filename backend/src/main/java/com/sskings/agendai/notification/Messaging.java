package com.sskings.agendai.notification;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.rabbitmq.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Topologia no RabbitMQ (declarada pela própria aplicação ao conectar; filas quorum, em disco):
 * <ul>
 *   <li>{@code agendai.events}: os eventos do outbox. O que não tem fila para ir (binding apagado, tipo novo sem
 *   consumidor) cai no exchange alternativo e fica em {@code agendai.unrouted} — nada some calado depois da confirmação.</li>
 *   <li>{@code agendai.notifications.email}: os e-mails. Falha transitória (provedor fora) vai para a fila de espera, que
 *   a devolve depois de {@code agendai.worker.retry-delay}; esgotadas as tentativas, ou mensagem malformada, vai para a
 *   fila de falhas (DLQ), onde fica até 7 dias para inspeção.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class Messaging {

    public static final String EXCHANGE = "agendai.events";
    public static final String UNROUTED_EXCHANGE = "agendai.events.unrouted";
    public static final String UNROUTED = "agendai.unrouted";
    /** Tipo do evento (routing key), versionado: um formato novo é um tipo novo. */
    public static final String EMAIL = "notification.email.v1";
    public static final String EMAIL_QUEUE = "agendai.notifications.email";
    public static final String EMAIL_WAIT = "agendai.notifications.email.wait";
    public static final String EMAIL_DLQ = "agendai.notifications.email.dlq";
    static final long PARKED_TTL_MS = Duration.ofDays(7).toMillis();
    static final int PARKED_MAX = 10_000;

    @Bean
    TopicExchange events() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).alternate(UNROUTED_EXCHANGE).build();
    }

    @Bean
    FanoutExchange unroutedExchange() {
        return ExchangeBuilder.fanoutExchange(UNROUTED_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue unrouted() {
        return QueueBuilder.durable(UNROUTED).quorum().ttl((int) PARKED_TTL_MS).maxLength(PARKED_MAX).build();
    }

    @Bean
    Binding unroutedBinding(Queue unrouted, FanoutExchange unroutedExchange) {
        return BindingBuilder.bind(unrouted).to(unroutedExchange);
    }

    @Bean
    Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE).quorum().build();
    }

    @Bean
    Binding emailBinding(Queue emailQueue, TopicExchange events) {
        return BindingBuilder.bind(emailQueue).to(events).with(EMAIL);
    }

    /** Espera antes da nova tentativa: vencido o prazo, volta à fila dos e-mails (pelo exchange dos eventos). */
    @Bean
    Queue emailWait(@Value("${agendai.worker.retry-delay:PT1M}") Duration delay) {
        return QueueBuilder.durable(EMAIL_WAIT).quorum().ttl((int) delay.toMillis())
                .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(EMAIL)
                // a volta não se perde: "pelo menos uma vez" no dead-lettering da quorum (exige reject-publish)
                .withArgument("x-dead-letter-strategy", "at-least-once").overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    @Bean
    Queue emailDeadLetters() {
        return QueueBuilder.durable(EMAIL_DLQ).quorum().ttl((int) PARKED_TTL_MS).maxLength(PARKED_MAX).build();
    }

    /** O publicador usa a própria conexão: broker bloqueando publicações (alarme de memória) não para os consumidores. */
    @Bean
    RabbitTemplateCustomizer publisherConnection() {
        return t -> t.setUsePublisherConnection(true);
    }
}
