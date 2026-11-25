package com.sskings.agendai.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Profissional × serviço que ele faz (com o tenant_id, para a FK composta e o RLS). */
@Entity
@Table(name = "professional_services")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ProfessionalService implements Persistable<ProfessionalService.Link> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;


    @Embeddable
    record Link(@Column(name = "professional_id") UUID professionalId, @Column(name = "service_id") UUID serviceId)
            implements Serializable {}

    @EmbeddedId
    private Link id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    ProfessionalService(UUID tenantId, UUID professionalId, UUID serviceId) {
        this.id = new Link(professionalId, serviceId);
        this.fresh = true;
        this.tenantId = tenantId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }
}
