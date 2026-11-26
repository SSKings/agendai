package com.sskings.agendai.publicbooking;

import com.sskings.agendai.db.TenantDataChanged;
import com.sskings.agendai.schedule.ScheduleDtos.AvailabilityView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** O cache nunca derruba a página: Redis fora do ar → calcula direto. */
class SlotCacheTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-30");
    static final AvailabilityView VIEW = new AvailabilityView(DAY, "America/Sao_Paulo",
            List.of(OffsetDateTime.parse("2026-11-30T09:00-03:00"), OffsetDateTime.parse("2026-11-30T09:30-03:00")));

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Redis fora do ar na leitura, na gravação ou ao renovar: a resposta sai calculada, sem erro")
    void redisDown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        AtomicInteger computed = new AtomicInteger();
        SlotCache cache = new SlotCache(redis);
        assertThat(cache.get(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DAY, () -> {
            computed.incrementAndGet();
            return VIEW;
        })).isEqualTo(VIEW);
        assertThat(computed).hasValue(1);

        // lê (nada guardado), mas não consegue gravar
        StringRedisTemplate half = mock(StringRedisTemplate.class);
        ValueOperations<String, String> halfOps = mock(ValueOperations.class);
        when(half.opsForValue()).thenReturn(halfOps);
        doThrow(new RedisConnectionFailureException("down")).when(halfOps).set(anyString(), anyString(), any(Duration.class));
        assertThat(new SlotCache(half).get(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DAY, () -> VIEW)).isEqualTo(VIEW);

        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        cache.changed(new TenantDataChanged(UUID.randomUUID()));      // não lança
        // nem com um erro que não é de acesso a dados (o commit já aconteceu: virar 500 faria o cliente repetir)
        doThrow(new IllegalStateException("desligando")).when(ops).increment(anyString());
        cache.changed(new TenantDataChanged(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Formato guardado: ida e volta, com e sem horários")
    void roundTrip() {
        assertThat(SlotCache.decode(DAY, SlotCache.encode(VIEW))).isEqualTo(VIEW);
        AvailabilityView empty = new AvailabilityView(DAY, "America/Sao_Paulo", List.of());
        assertThat(SlotCache.encode(empty)).isEqualTo("America/Sao_Paulo|");
        assertThat(SlotCache.decode(DAY, SlotCache.encode(empty))).isEqualTo(empty);
    }
}
