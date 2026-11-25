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

/** Quem atende. Pode ou não ter conta no app (o vínculo com um usuário vem com o convite da equipe). */
@Entity
@Table(name = "professionals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Professional implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;


    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    /** Conta vinculada pelo convite (só o banco grava: função redeem_invite, V5). */
    @Column(name = "user_id", insertable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    Professional(UUID tenantId, String name, boolean active, Instant now) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.createdAt = now;
        change(name, active);
    }

    void change(String name, boolean active) {
        this.name = name;
        this.active = active;
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
