package com.sskings.agendai.publicbooking;

import com.sskings.agendai.catalog.CatalogService;
import com.sskings.agendai.schedule.ScheduleService;
import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.tenant.PublicAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Os métodos sem guard próprio só existem dentro de uma transação que já passou por um (o público ou o do painel). */
class PublicGuardTest extends IntegrationTest {

    @Autowired PublicAccess access;
    @Autowired CatalogService catalog;
    @Autowired ScheduleService schedule;

    @Test
    @DisplayName("Fora de transação: recusados (Propagation.MANDATORY)")
    void requireTransaction() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> access.enter("x")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> catalog.activeServices(id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> catalog.activeProfessionalsFor(id, id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> schedule.slots(id, id, id, LocalDate.now())).isInstanceOf(IllegalTransactionStateException.class);
    }
}
