package com.sskings.agendai.publicbooking;

import com.sskings.agendai.db.TenantDataChanged;
import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cache dos horários livres da página pública no Redis (ADR-012). A chave leva a VERSÃO do estabelecimento, que sobe a
 * cada escrita gravada nele ({@link TenantDataChanged}): agendou, cancelou, mudou expediente, bloqueio ou serviço → as
 * próximas leituras usam outra chave e recalculam (a antiga vence sozinha). O TTL curto cobre o que muda sem escrita
 * (o pendente que venceu, a hora que passou) — e o banco continua sendo a garantia final (a reserva confere de novo).
 * Redis fora do ar: calcula direto, sem cache.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SlotCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    AvailabilityView get(UUID tenantId, UUID professionalId, UUID serviceId, LocalDate date, Supplier<AvailabilityView> compute) {
        String key;
        try {
            String version = redis.opsForValue().get(versionKey(tenantId));
            key = "slots:" + tenantId + ":" + (version == null ? "0" : version) + ":" + professionalId + ":" + serviceId + ":" + date;
            String cached = redis.opsForValue().get(key);
            if (cached != null) return decode(date, cached);
        } catch (DataAccessException e) {
            log.warn("Cache dos horários indisponível: calculando direto");
            return compute.get();
        }
        AvailabilityView fresh = compute.get();
        try {
            redis.opsForValue().set(key, encode(fresh), TTL);
        } catch (DataAccessException e) {
            log.warn("Cache dos horários indisponível: resposta sem guardar");
        }
        return fresh;
    }

    /** Depois do commit de uma escrita no estabelecimento. Falhar aqui não desfaz nada: o TTL limita o atraso. */
    @EventListener
    void changed(TenantDataChanged e) {
        // a versão não expira (uma chave pequena por estabelecimento): se voltasse a 1, entradas velhas da versão 1
        // ainda vivas seriam servidas. Qualquer erro aqui não pode virar 500 depois do commit.
        try {
            redis.opsForValue().increment(versionKey(e.tenantId()));
        } catch (RuntimeException ex) {
            log.warn("Cache dos horários: versão não avançou (o TTL de {} s limita o atraso)", TTL.toSeconds());
        }
    }

    private static String versionKey(UUID tenantId) {
        return "slots:v:" + tenantId;
    }

    /** "fuso|início1,início2,..." (ISO com deslocamento). */
    static String encode(AvailabilityView v) {
        return v.timeZone() + "|" + String.join(",", v.slots().stream().map(OffsetDateTime::toString).toList());
    }

    static AvailabilityView decode(LocalDate date, String s) {
        int bar = s.indexOf('|');
        String list = s.substring(bar + 1);
        List<OffsetDateTime> slots = list.isEmpty() ? List.of() : Arrays.stream(list.split(",")).map(OffsetDateTime::parse).toList();
        return new AvailabilityView(date, s.substring(0, bar), slots);
    }
}
