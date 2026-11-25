package com.sskings.agendai.catalog;

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

/** Convite para um profissional entrar na equipe. No banco, só o hash do código; aceitar é a função redeem_invite. */
@Entity
@Table(name = "invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Invite implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    Invite(UUID tenantId, UUID professionalId, String tokenHash, UUID createdBy, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.professionalId = professionalId;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.expiresAt = expiresAt;
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
