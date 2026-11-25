package com.sskings.agendai.auth;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.config.AgendaiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Rotação de refresh tokens (ADR-005). Cada uso troca o token por um novo da mesma família. Se um token JÁ trocado
 * aparecer de novo, alguém tem uma cópia (roubo): a família inteira é revogada e as duas pontas precisam logar de novo.
 */
@Slf4j
@Service
class RefreshTokens {

    static final String INVALID = "Invalid session";

    private final RefreshTokenRepository tokens;
    private final Duration ttl;
    private final Clock clock;

    RefreshTokens(RefreshTokenRepository tokens, AgendaiProperties props, Clock clock) {
        this.tokens = tokens;
        this.ttl = Duration.ofDays(props.refresh().ttlDays());
        this.clock = clock;
    }

    record Issued(String raw, UUID userId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    String issue(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    /** Usa o token e devolve o novo. Inválido, vencido, revogado ou reusado → 401 (sem dizer qual). */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    Issued rotate(String raw) {
        if (!Tokens.wellFormed(raw)) throw ApiException.unauthorized(INVALID);
        RefreshToken current = tokens.findByHashForUpdate(Tokens.sha256(raw)).orElseThrow(() -> ApiException.unauthorized(INVALID));
        Instant now = clock.instant();
        if (current.getUsedAt() != null) {
            tokens.revokeFamily(current.getFamilyId(), now);   // gravado mesmo com o 401 (noRollbackFor)
            log.warn("Refresh token reusado: família {} do usuário {} revogada", current.getFamilyId(), current.getUserId());
            throw ApiException.unauthorized(INVALID);
        }
        if (current.getRevokedAt() != null || !now.isBefore(current.getExpiresAt())) throw ApiException.unauthorized(INVALID);
        current.setUsedAt(now);
        return new Issued(issue(current.getUserId(), current.getFamilyId()), current.getUserId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void revokeAll(UUID userId) {
        tokens.revokeAllOf(userId, clock.instant());
    }

    private String issue(UUID userId, UUID familyId) {
        String raw = Tokens.random();
        Instant now = clock.instant();
        tokens.save(new RefreshToken(userId, familyId, Tokens.sha256(raw), now, now.plus(ttl)));
        return raw;
    }
}
