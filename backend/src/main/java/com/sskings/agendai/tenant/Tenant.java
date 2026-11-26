package com.sskings.agendai.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Estabelecimento. O isolamento entre eles é a regra mais importante da segurança (ADR-006). */
@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 60, updatable = false)
    private String slug;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Política para o cliente (V8): antecedência mínima, prazo para cancelar/remarcar sozinho, dias que a agenda abre. */
    @Column(name = "min_notice_minutes", nullable = false)
    private int minNoticeMinutes = 60;

    @Column(name = "cancel_until_hours", nullable = false)
    private int cancelUntilHours = 24;

    @Column(name = "booking_window_days", nullable = false)
    private int bookingWindowDays = 60;

    public Tenant(String name, String slug, String timeZone, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.slug = slug;
        this.timeZone = timeZone;
        this.createdAt = createdAt;
    }

    void changePolicy(int minNoticeMinutes, int cancelUntilHours, int bookingWindowDays) {
        this.minNoticeMinutes = minNoticeMinutes;
        this.cancelUntilHours = cancelUntilHours;
        this.bookingWindowDays = bookingWindowDays;
    }
}
