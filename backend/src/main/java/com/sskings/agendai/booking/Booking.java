package com.sskings.agendai.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.DynamicUpdate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Agendamento. A coluna {@code period} ([starts_at, blocked_until)) é gerada pelo banco e não é mapeada: é dela que a
 * restrição de exclusão cuida (ADR-002).
 */
@Entity
@Table(name = "bookings")
@DynamicUpdate   // UPDATE só do que mudou: as tentativas do código são contadas por UPDATE direto (BookingRepository)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Booking implements Persistable<UUID> {

    /** Id gerado aqui: sem isto o save() faria merge (um SELECT antes de cada INSERT). */
    @Transient
    private boolean fresh;

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "professional_id", nullable = false, updatable = false)
    private UUID professionalId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Column(name = "blocked_until", nullable = false, updatable = false)
    private Instant blockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "price_cents", nullable = false, updatable = false)
    private int priceCents;

    @Column(length = 200, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Pela página pública: até quando o código vale (e o pendente segura o horário). */
    @Column(name = "pending_until")
    private Instant pendingUntil;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "code_attempts", nullable = false, insertable = false, updatable = false)
    private int codeAttempts;

    @Column(name = "access_hash", length = 64, updatable = false)
    private String accessHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private BookingSource source = BookingSource.PANEL;

    /** Pela página: quem confirmou o código (o cadastro por telefone pode ser de outra pessoa). */
    @Column(name = "contact_name", length = 100, updatable = false)
    private String contactName;

    @Column(name = "contact_email", length = 254, updatable = false)
    private String contactEmail;

    /** Remarcações pelo cliente até chegar neste (o novo herda do antigo + 1; no máximo 3). */
    @Column(nullable = false, updatable = false)
    private int reschedules;

    Booking(UUID tenantId, UUID professionalId, UUID serviceId, UUID customerId, Instant startsAt, Instant endsAt,
            Instant blockedUntil, int priceCents, String note, Instant now) {
        this.id = UUID.randomUUID();
        this.fresh = true;
        this.tenantId = tenantId;
        this.professionalId = professionalId;
        this.serviceId = serviceId;
        this.customerId = customerId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.blockedUntil = blockedUntil;
        this.status = BookingStatus.CONFIRMED;
        this.priceCents = priceCents;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Agendamento pela página pública: PENDING até o cliente confirmar o código (só os hashes ficam no banco). */
    static Booking pending(UUID tenantId, UUID professionalId, UUID serviceId, UUID customerId, Instant startsAt, Instant endsAt,
                           Instant blockedUntil, int priceCents, Instant now, Instant pendingUntil, java.util.function.Function<UUID, String> codeHashOf,
                           String accessHash, String contactName, String contactEmail) {
        Booking b = new Booking(tenantId, professionalId, serviceId, customerId, startsAt, endsAt, blockedUntil, priceCents, null, now);
        b.status = BookingStatus.PENDING;
        b.source = BookingSource.PUBLIC;
        b.pendingUntil = pendingUntil;
        b.codeHash = codeHashOf.apply(b.id);
        b.accessHash = accessHash;
        b.contactName = contactName;
        b.contactEmail = contactEmail;
        return b;
    }

    /** Remarcação pelo cliente: o mesmo serviço, cliente e contato, já confirmada, levando o token de acesso do antigo. */
    static Booking rescheduled(Booking old, UUID professionalId, Instant startsAt, Instant endsAt, Instant blockedUntil, int priceCents,
                               Instant now) {
        Booking b = new Booking(old.tenantId, professionalId, old.serviceId, old.customerId, startsAt, endsAt, blockedUntil, priceCents, null, now);
        b.source = BookingSource.PUBLIC;
        b.accessHash = old.accessHash;
        b.contactName = old.contactName;
        b.contactEmail = old.contactEmail;
        b.reschedules = old.reschedules + 1;
        return b;
    }

    void changeStatus(BookingStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
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
