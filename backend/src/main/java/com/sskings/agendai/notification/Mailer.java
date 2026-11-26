package com.sskings.agendai.notification;

/**
 * Envio de e-mail. Nesta fase é síncrono, dentro da requisição (código de confirmação): se o provedor falhar, a
 * operação inteira falha e nada fica pela metade. Na fase 3 passa a sair pelo outbox e pelo worker (ADR-003).
 */
public interface Mailer {

    /** Texto puro (nada de HTML montado com dado do cliente). */
    void send(String to, String subject, String text);
}
