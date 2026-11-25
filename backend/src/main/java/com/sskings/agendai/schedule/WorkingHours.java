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

import java.util.UUID;

/** Uma faixa de trabalho de um profissional num dia da semana, em minutos desde 00:00 no fuso do estabelecimento. */
@Entity
@Table(name = "working_hours")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WorkingHours implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    /** ISO-8601: 1 = segunda … 7 = domingo. */
    @Column(nullable = false, updatable = false)
    private int weekday;

    @Column(name = "start_minute", nullable = false, updatable = false)
    private int startMinute;

    @Column(name = "end_minute", nullable = false, updatable = false)
    private int endMinute;

    WorkingHours(UUID tenantId, UUID professionalId, int weekday, int startMinute, int endMinute) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.professionalId = professionalId;
        this.weekday = weekday;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
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
