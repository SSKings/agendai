package com.sskings.agendai.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** As decisões do worker e do publicador que o teste de integração não força de forma determinística. */
class EmailWorkerTest {

    final Mailer mailer = mock(Mailer.class);
    final TransactionTemplate tx = mock(TransactionTemplate.class);
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final RabbitOperations ops = mock(RabbitOperations.class);
    final EmailWorker worker = new EmailWorker(mailer, tx, rabbit, Clock.systemUTC(), 4);

    EmailWorkerTest() {
        org.mockito.Mockito.doAnswer(i -> ((RabbitOperations.OperationsCallback<?>) i.getArgument(0)).doInRabbit(ops)).when(rabbit).invoke(any());
    }

    static Message msg(String body, String id, Integer attempt) {
        MessageBuilder b = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8));
        if (id != null) b.setMessageId(id);
        if (attempt != null) b.setHeader(EmailWorker.ATTEMPT, attempt);
        return b.build();
    }

    static final String OK = "{\"to\":\"a@x.com\",\"subject\":\"S\",\"text\":\"T\"}";

    /** Para onde foi encaminhada (ou null) e com que tentativa. */
    Message forwarded(String queue) {
        ArgumentCaptor<Message> m = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq(""), eq(queue), m.capture());
        verify(ops).waitForConfirmsOrDie(anyLong());
        return m.getValue();
    }

    @Test
    @DisplayName("Malformada (sem id, id inválido, JSON quebrado, campo faltando ou vazio): fila de falhas, sem tentar enviar")
    void malformed() {
        for (Message m : new Message[]{msg(OK, null, null), msg(OK, "x", null), msg("nada", UUID.randomUUID().toString(), null),
                msg("{\"to\":\"a@x.com\",\"text\":\"T\"}", UUID.randomUUID().toString(), null),
                msg("{\"to\":\"a@x.com\",\"subject\":\" \",\"text\":\"T\"}", UUID.randomUUID().toString(), null),
                msg("{\"to\":\"a@x.com\",\"subject\":1,\"text\":\"T\"}", UUID.randomUUID().toString(), null)}) {
            EmailWorker w = new EmailWorker(mailer, tx, rabbit, Clock.systemUTC(), 4);
            RabbitOperations o = mock(RabbitOperations.class);
            org.mockito.Mockito.doAnswer(i -> ((RabbitOperations.OperationsCallback<?>) i.getArgument(0)).doInRabbit(o)).when(rabbit).invoke(any());
            w.on(m);
            verify(o).send(eq(""), eq(Messaging.EMAIL_DLQ), any());
        }
        verifyNoInteractions(tx, mailer);
    }

    @Test
    @DisplayName("Campo faltando é recusado como malformado (não estoura com NullPointerException)")
    void missingField() {
        Message m = msg("{\"to\":\"a@x.com\",\"text\":\"T\"}", UUID.randomUUID().toString(), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EmailWorker.Email.of(m))
                .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("Campo ausente: subject");
    }

    @Test
    @DisplayName("Envio falhou: fila de espera com a tentativa contada; na última, fila de falhas")
    void retryThenPark() {
        doThrow(new IllegalStateException("smtp fora")).when(tx).executeWithoutResult(any());
        worker.on(msg(OK, UUID.randomUUID().toString(), null));
        assertThat(EmailWorker.attemptOf(forwarded(Messaging.EMAIL_WAIT))).isEqualTo(1);

        RabbitOperations last = mock(RabbitOperations.class);
        org.mockito.Mockito.doAnswer(i -> ((RabbitOperations.OperationsCallback<?>) i.getArgument(0)).doInRabbit(last)).when(rabbit).invoke(any());
        worker.on(msg(OK, UUID.randomUUID().toString(), 3));
        verify(last).send(eq(""), eq(Messaging.EMAIL_DLQ), any());
    }

    @Test
    @DisplayName("Entregou: nada é encaminhado; cabeçalho de tentativa estranho conta como zero")
    void delivered() {
        worker.on(msg(OK, UUID.randomUUID().toString(), null));
        verify(tx).executeWithoutResult(any());
        verify(ops, never()).send(any(), any(), any(Message.class));
        Message odd = MessageBuilder.withBody(new byte[0]).setHeader(EmailWorker.ATTEMPT, "dois").build();
        assertThat(EmailWorker.attemptOf(odd)).isZero();
    }

    @Test
    @DisplayName("Publicador: broker fora → 0, sem lançar; volta → publica (o aviso no log só na mudança)")
    void publisherSwallows() {
        TransactionTemplate t = mock(TransactionTemplate.class);
        when(t.execute(any())).thenThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("down")))
                .thenThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("down")))
                .thenReturn(2).thenReturn(0);
        OutboxPublisher p = new OutboxPublisher(mock(RabbitTemplate.class), t, Clock.systemUTC());
        assertThat(p.run()).isZero();
        assertThat(p.run()).isZero();
        assertThat(p.run()).isEqualTo(2);
        assertThat(p.run()).isZero();
    }
}
