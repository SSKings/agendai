package com.sskings.agendai.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O id nasce na aplicação: a entidade diz que é nova até ser gravada ou lida (senão o save() faria SELECT + INSERT). */
class CatalogEntitiesTest {

    @Test
    @DisplayName("Serviço, profissional e vínculo: novos ao criar; deixam de ser novos depois de gravados ou carregados")
    void newUntilStored() {
        UUID tenant = UUID.randomUUID();
        Service s = new Service(tenant, new CatalogDtos.ServiceRequest("Corte", 30, 0, 0, true), Instant.now());
        Professional p = new Professional(tenant, "João", true, Instant.now());
        ProfessionalService link = new ProfessionalService(tenant, p.getId(), s.getId());
        assertThat(s.isNew()).isTrue();
        assertThat(p.isNew()).isTrue();
        assertThat(link.isNew()).isTrue();
        s.stored();
        p.stored();
        link.stored();
        assertThat(s.isNew()).isFalse();
        assertThat(p.isNew()).isFalse();
        assertThat(link.isNew()).isFalse();
    }
}
