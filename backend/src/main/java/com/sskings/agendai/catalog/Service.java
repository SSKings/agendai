package com.sskings.agendai.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Serviço oferecido pelo estabelecimento: duração, intervalo depois (preparo/limpeza) e preço em centavos. */
@Entity
@Table(name = "services")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Service implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;


    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    Service(UUID tenantId, CatalogDtos.ServiceRequest r, Instant now) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.createdAt = now;
        apply(r);
    }

    void apply(CatalogDtos.ServiceRequest r) {
        this.name = r.name();
        this.durationMinutes = r.durationMinutes();
        this.bufferMinutes = r.bufferMinutes();
        this.priceCents = r.priceCents();
        this.active = r.active();
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
