package com.sskings.agendai.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

    @Embeddable
    public record Key(@Column(name = "tenant_id") UUID tenantId, @Column(name = "user_id") UUID userId) implements Serializable {}

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    public Membership(UUID tenantId, UUID userId, Role role, Instant joinedAt) {
        this.id = new Key(tenantId, userId);
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public UUID getTenantId() {
        return id.tenantId();
    }
}
