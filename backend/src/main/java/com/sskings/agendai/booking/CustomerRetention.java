package com.sskings.agendai.booking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Retenção (LGPD): anonimiza, em todos os estabelecimentos, tentativas pela página nunca confirmadas (30 dias) e
 * clientes sem agendamento há 24 meses (função do banco, V9). Lotes em transações curtas, um atrás do outro até a fila
 * esvaziar (ou até {@code maxBatches}, para uma passada não durar para sempre): seguro com várias instâncias (SKIP
 * LOCKED).
 */
@Slf4j
@Component
class CustomerRetention {

    private final CustomerRepository customers;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final int batch;
    private final int maxBatches;

    CustomerRetention(CustomerRepository customers, Clock clock, TransactionTemplate tx,
                      @Value("${agendai.retention.batch:500}") int batch, @Value("${agendai.retention.max-batches:100}") int maxBatches) {
        this.customers = customers;
        this.clock = clock;
        this.tx = tx;
        this.batch = batch;
        this.maxBatches = maxBatches;
    }

    @Scheduled(fixedDelayString = "${agendai.retention.every:PT6H}", initialDelayString = "${agendai.retention.every:PT6H}")
    public int run() {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            Integer n = tx.execute(s -> customers.anonymizeStale(clock.instant(), batch));
            total += n;
            if (n < batch) break;
        }
        if (total > 0) log.info("Clientes anonimizados pela retenção: {}", total);
        return total;
    }
}
