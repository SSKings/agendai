package com.sskings.agendai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limite por IP nas rotas sem login (cadastro, login, renovação), por minuto, **no Redis**: vale entre todas as
 * instâncias da API (ADR-007). IPv6 conta pelo bloco /64.
 * <p>
 * Redis fora do ar → deixa passar e registra (alerta): a garantia contra força bruta é o bloqueio por conta, no banco;
 * derrubar o login de todos por causa do Redis seria pior.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    static final String PREFIX = "rl:auth:";

    private final AtomicLong lastWarnMinute = new AtomicLong(-1);
    private final int perMinute;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimitFilter(int perMinute, StringRedisTemplate redis, Clock clock) {
        this.perMinute = perMinute;
        this.redis = redis;
        this.clock = clock;
    }

    static boolean limited(String method, String path) {
        return "POST".equals(method) && path.startsWith("/api/auth/") && !path.equals("/api/auth/logout");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limited(request.getMethod(), RequestPaths.of(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long seconds = clock.instant().getEpochSecond();
        String key = PREFIX + sourceKey(request.getRemoteAddr()) + ":" + seconds / 60;
        long count;
        try {
            count = redis.opsForValue().increment(key);       // nunca nulo fora de pipeline/transação do Redis
            if (count == 1) redis.expire(key, Duration.ofSeconds(120));
        } catch (DataAccessException e) {
            if (shouldWarn(seconds / 60)) {
                log.warn("Limite por IP desligado: Redis indisponível ({})", e.getClass().getSimpleName());
            }
            chain.doFilter(request, response);
            return;
        }
        if (count > perMinute) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(60 - seconds % 60));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Um aviso por minuto (por instância) enquanto o Redis estiver fora: sem inundar o log a cada requisição. */
    boolean shouldWarn(long minute) {
        return lastWarnMinute.getAndSet(minute) != minute;    // atômico: na corrida, só uma requisição avisa
    }

    /** IPv4 inteiro (o mapeado em IPv6 vira IPv4); IPv6 pelo /64 (o cliente troca de endereço no bloco à vontade). */
    static String sourceKey(String ip) {
        if (ip == null || !ip.contains(":")) return String.valueOf(ip);
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) return InetAddress.getByAddress(b).getHostAddress();
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64", b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
