package com.sskings.agendai.security;

import com.sskings.agendai.common.ClientSource;

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
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

/**
 * Limite por IP nas rotas sem login, por minuto, **no Redis**: vale entre todas as instâncias da API (ADR-007). IPv6
 * conta pelo bloco /64. Dois orçamentos separados: autenticação (cadastro, login, renovação — pequeno, contra força
 * bruta) e página pública (maior, contra robô varrendo a agenda); um não consome o do outro.
 * <p>
 * Redis fora do ar → deixa passar e registra (alerta): a garantia contra força bruta é o bloqueio por conta, no banco;
 * derrubar o login de todos por causa do Redis seria pior.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    static final String PREFIX = "rl:auth:";
    static final String PUBLIC_PREFIX = "rl:public:";

    private final AtomicLong lastWarnMinute = new AtomicLong(-1);
    private final String prefix;
    private final BiPredicate<String, String> applies;
    private final int perMinute;
    /** Teto por estabelecimento (todas as origens somadas) na página pública; 0 = sem esse teto. */
    private final int perBusinessMinute;
    private final StringRedisTemplate redis;
    private final Clock clock;

    /** Orçamento da autenticação. */
    public RateLimitFilter(int perMinute, StringRedisTemplate redis, Clock clock) {
        this(PREFIX, RateLimitFilter::limited, perMinute, 0, redis, clock);
    }

    private RateLimitFilter(String prefix, BiPredicate<String, String> applies, int perMinute, int perBusinessMinute,
                            StringRedisTemplate redis, Clock clock) {
        this.prefix = prefix;
        this.applies = applies;
        this.perMinute = perMinute;
        this.perBusinessMinute = perBusinessMinute;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Orçamento da página pública (toda rota /api/public/...): por IP e, somando todas as origens, por estabelecimento —
     * um robô com muitos IPs também não varre a agenda de um estabelecimento sem limite.
     */
    public static RateLimitFilter forPublicPages(int perMinute, int perBusinessMinute, StringRedisTemplate redis, Clock clock) {
        return new RateLimitFilter(PUBLIC_PREFIX, RateLimitFilter::publicPage, perMinute, perBusinessMinute, redis, clock);
    }

    /** "/api/public/barbearia/services" → "barbearia" (o link, que identifica o estabelecimento). */
    static String businessOf(String path) {
        String rest = path.substring("/api/public/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    static boolean limited(String method, String path) {
        return "POST".equals(method) && path.startsWith("/api/auth/") && !path.equals("/api/auth/logout");
    }

    static boolean publicPage(String method, String path) {
        return path.startsWith("/api/public/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !applies.test(request.getMethod(), RequestPaths.of(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long seconds = clock.instant().getEpochSecond();
        long minute = seconds / 60;
        boolean over;
        try {
            over = count(prefix + sourceKey(request.getRemoteAddr()) + ":" + minute) > perMinute;
            if (!over && perBusinessMinute > 0) {
                over = count(prefix + "t:" + businessOf(RequestPaths.of(request)) + ":" + minute) > perBusinessMinute;
            }
        } catch (DataAccessException e) {
            if (shouldWarn(seconds / 60)) {
                log.warn("Limite por IP desligado: Redis indisponível ({})", e.getClass().getSimpleName());
            }
            chain.doFilter(request, response);
            return;
        }
        if (over) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(60 - seconds % 60));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private long count(String key) {
        long count = redis.opsForValue().increment(key);       // nunca nulo fora de pipeline/transação do Redis
        if (count == 1) redis.expire(key, Duration.ofSeconds(120));
        return count;
    }

    /** Um aviso por minuto (por instância) enquanto o Redis estiver fora: sem inundar o log a cada requisição. */
    boolean shouldWarn(long minute) {
        return lastWarnMinute.getAndSet(minute) != minute;    // atômico: na corrida, só uma requisição avisa
    }

    /** IPv4 inteiro; IPv6 pelo /64 ({@link ClientSource}). */
    public static String sourceKey(String ip) {
        return ClientSource.of(ip);
    }
}
