package com.sskings.agendai.security;

import com.sskings.agendai.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    @Test
    @DisplayName("Redis fora do ar: o pedido segue (a garantia contra força bruta é o bloqueio por conta, no banco)")
    void redisDownFailsOpen() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        RateLimitFilter f = new RateLimitFilter(1, redis, new MutableClock());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Só POST nas rotas de /api/auth (menos o logout, que exige login) é limitado")
    void limitedRoutes() {
        assertThat(RateLimitFilter.limited("POST", "/api/auth/login")).isTrue();
        assertThat(RateLimitFilter.limited("POST", "/api/auth/refresh")).isTrue();
        assertThat(RateLimitFilter.limited("POST", "/api/auth/logout")).isFalse();
        assertThat(RateLimitFilter.limited("GET", "/api/auth/login")).isFalse();
        assertThat(RateLimitFilter.limited("POST", "/api/me")).isFalse();
    }

    @Test
    @DisplayName("Origem: IPv4 é o IP; IPv4 escrito como IPv6 vira o IPv4; IPv6 é o /64; nulo vira 'null'")
    void sourceKey() {
        assertThat(RateLimitFilter.sourceKey("1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(RateLimitFilter.sourceKey("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(RateLimitFilter.sourceKey("2001:db8:abcd:ef01::1")).isEqualTo("2001:0db8:abcd:ef01::/64");
        assertThat(RateLimitFilter.sourceKey(null)).isEqualTo("null");
        assertThat(RateLimitFilter.sourceKey("zz:zz")).as("não é endereço").isEqualTo("zz:zz");
    }

    /** Redis simulado: devolve as contagens pedidas e registra as chaves. */
    private static StringRedisTemplate redisCounting(java.util.List<String> keys, long... counts) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        java.util.concurrent.atomic.AtomicInteger i = new java.util.concurrent.atomic.AtomicInteger();
        when(ops.increment(anyString())).thenAnswer(inv -> { keys.add(inv.getArgument(0)); return counts[i.getAndIncrement()]; });
        return redis;
    }

    private static MockHttpServletResponse post(RateLimitFilter f, String path, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRemoteAddr("7.7.7.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        return res;
    }

    @Test
    @DisplayName("Contagem no Redis por IP e minuto: a 1ª define a validade da chave; no limite passa; acima, 429 com JSON e Retry-After")
    void countingAndLimit() throws Exception {
        MutableClock clock = new MutableClock();
        clock.set(java.time.Instant.parse("2026-11-24T12:00:15Z"));
        java.util.List<String> keys = new java.util.ArrayList<>();
        StringRedisTemplate redis = redisCounting(keys, 1, 2, 3);
        RateLimitFilter f = new RateLimitFilter(2, redis, clock);
        MockFilterChain first = new MockFilterChain();
        post(f, "/api/auth/login", first);
        assertThat(first.getRequest()).isNotNull();
        verify(redis, times(1)).expire(anyString(), any(java.time.Duration.class));
        MockFilterChain second = new MockFilterChain();
        post(f, "/api/auth/login", second);
        assertThat(second.getRequest()).as("no limite ainda passa").isNotNull();
        verify(redis, times(1)).expire(anyString(), any(java.time.Duration.class));    // só na primeira
        MockFilterChain third = new MockFilterChain();
        MockHttpServletResponse blocked = post(f, "/api/auth/login", third);
        assertThat(third.getRequest()).isNull();
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("45");
        assertThat(blocked.getContentType()).startsWith("application/json");
        assertThat(blocked.getContentAsString()).isEqualTo("{\"status\":429,\"message\":\"Too many requests\"}");
        long minute = clock.instant().getEpochSecond() / 60;
        assertThat(keys).containsOnly("rl:auth:7.7.7.7:" + minute);
    }

    @Test
    @DisplayName("Rotas fora do limite nem tocam no Redis")
    void otherRoutesSkipRedis() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RateLimitFilter f = new RateLimitFilter(1, redis, new MutableClock());
        MockFilterChain chain = new MockFilterChain();
        post(f, "/api/me", chain);
        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("Redis fora: um aviso no log por minuto, não um por requisição")
    void warnOncePerMinute() {
        RateLimitFilter f = new RateLimitFilter(1, mock(StringRedisTemplate.class), new MutableClock());
        assertThat(f.shouldWarn(100)).isTrue();
        assertThat(f.shouldWarn(100)).isFalse();
        assertThat(f.shouldWarn(100)).isFalse();
        assertThat(f.shouldWarn(101)).isTrue();
    }
}
