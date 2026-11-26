package com.sskings.agendai.publicbooking;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.common.ClientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Reserva temporária no Redis (ADR-007): ao escolher o horário, ele fica segurado por 5 min enquanto o cliente
 * preenche os dados. É experiência, não garantia — quem garante é a restrição de exclusão no banco (ADR-002).
 * <ul>
 *   <li>{@code hold:s:{estab}:{prof}:{início}} → token, com SET NX: dois clientes não seguram o mesmo início.</li>
 *   <li>{@code hold:t:{token}} → o que foi segurado; o token (256 bits) só o cliente tem.</li>
 *   <li>{@code hold:ip:{estab}:{origem}} → reservas abertas por origem, por estabelecimento: no máximo
 *       {@link #MAX_PER_SOURCE} (robô não segura a agenda inteira; tentativa recusada não conta).</li>
 * </ul>
 * Redis fora do ar → 503: a página pública não reserva sem ele (o painel continua agendando).
 */
@Component
@RequiredArgsConstructor
class Holds {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_PER_SOURCE = 3;
    static final String TAKEN = "Time slot not available";
    static final String TOO_MANY = "Too many holds";
    static final String UNAVAILABLE = "Busy, try again";

    record Hold(UUID tenantId, UUID professionalId, UUID serviceId, LocalDateTime start) {
        String encode() {
            return tenantId + "|" + professionalId + "|" + serviceId + "|" + start;
        }

        static Hold decode(String v) {
            String[] p = v.split("\\|");
            return new Hold(UUID.fromString(p[0]), UUID.fromString(p[1]), UUID.fromString(p[2]), LocalDateTime.parse(p[3]));
        }
    }

    private final StringRedisTemplate redis;
    private final Clock clock;

    /** Segura o horário; devolve o token. */
    String create(Hold h, String ip) {
        String token = Tokens.random();
        try {
            String source = "hold:ip:" + h.tenantId() + ":" + ClientSource.of(ip);
            long open = redis.opsForValue().increment(source);
            if (open == 1) redis.expire(source, TTL);
            if (open > MAX_PER_SOURCE) {
                redis.opsForValue().decrement(source);
                throw ApiException.tooManyRequests(TOO_MANY);
            }
            Boolean ok = redis.opsForValue().setIfAbsent(slotKey(h), token, TTL);
            if (!Boolean.TRUE.equals(ok)) {
                redis.opsForValue().decrement(source);           // horário recém-tomado: não gasta a reserva da origem
                throw ApiException.conflict(TAKEN);
            }
            redis.opsForValue().set("hold:t:" + token, h.encode(), TTL);
        } catch (DataAccessException e) {
            throw ApiException.unavailable(UNAVAILABLE);
        }
        return token;
    }

    Instant expiresAt() {
        return clock.instant().plus(TTL);
    }

    /** Usa a reserva (uma vez só): devolve o que foi segurado, ou null se venceu, já foi usada ou nunca existiu. */
    Hold take(String token) {
        try {
            String v = redis.opsForValue().getAndDelete("hold:t:" + token);
            if (v == null) return null;
            Hold h = Hold.decode(v);
            redis.delete(slotKey(h));
            return h;
        } catch (DataAccessException e) {
            throw ApiException.unavailable(UNAVAILABLE);
        }
    }

    private static String slotKey(Hold h) {
        return "hold:s:" + h.tenantId() + ":" + h.professionalId() + ":" + h.start();
    }
}
