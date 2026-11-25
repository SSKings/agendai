package com.sskings.agendai.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Bloqueio pontual (folga, consulta médica, feriado), em UTC. Sem profissional = o estabelecimento inteiro. */
@Entity
@Table(name = "time_off")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TimeOff implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "professional_id", updatable = false)
    private UUID professionalId;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;

    @Column(length = 100, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    TimeOff(UUID tenantId, UUID professionalId, Instant startAt, Instant endAt, String reason, Instant now) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.professionalId = professionalId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
        this.createdAt = now;
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
