package com.sskings.agendai.auth;

import com.sskings.agendai.auth.AuthDtos.AuthResponse;
import com.sskings.agendai.auth.AuthDtos.ChangePasswordRequest;
import com.sskings.agendai.auth.AuthDtos.LoginRequest;
import com.sskings.agendai.auth.AuthDtos.RegisterRequest;
import com.sskings.agendai.auth.AuthDtos.UserView;
import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.common.Texts;
import com.sskings.agendai.config.AgendaiProperties;
import com.sskings.agendai.security.JwtService;
import com.sskings.agendai.security.PasswordStrength;
import com.sskings.agendai.tenant.TenantService;
import com.sskings.agendai.user.User;
import com.sskings.agendai.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cadastro, login, renovação da sessão, logout e troca de senha.
 * <ul>
 *   <li>Login com a mesma resposta (e o mesmo custo de BCrypt) para senha errada e e-mail inexistente.</li>
 *   <li>Bloqueio por conta: N falhas seguidas → 15 min; cada bloqueio seguido dobra (até 24 h); login certo zera.</li>
 *   <li>Logout e troca de senha revogam tudo: access tokens (versão) e refresh tokens (todas as famílias).</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthService {

    static final String INVALID_LOGIN = "Invalid email or password";
    static final String LOCKED = "Too many failed attempts. Try again later";
    static final Duration MAX_LOCK = Duration.ofHours(24);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokens refreshTokens;
    private final TenantService tenants;
    private final AgendaiProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, RefreshTokens refreshTokens,
                       TenantService tenants, AgendaiProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.tenants = tenants;
        this.props = props;
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-constant-time-0000");
    }

    /** Resposta para o corpo + refresh token para o cookie. */
    public record Session(AuthResponse response, String refreshToken) {}

    @Transactional
    public Session register(RegisterRequest req) {
        String name = Texts.clean(req.name());
        PasswordStrength.problem(req.password(), name, req.email()).ifPresent(p -> { throw ApiException.badRequest(p); });
        if (users.existsByEmail(req.email())) throw ApiException.conflict("Email already in use");
        User user = users.save(new User(name, req.email(), encoder.encode(req.password()), clock.instant()));
        tenants.createFor(user, Texts.clean(req.businessName()));
        return session(user);
    }

    @Transactional(noRollbackFor = ApiException.class)   // a falha contada precisa ser gravada mesmo com o 401
    public Session login(LoginRequest req) {
        User user = users.findByEmailForUpdate(req.email()).orElse(null);
        if (user == null) {
            encoder.matches(req.password(), dummyHash);   // mesmo custo: o tempo não revela quem tem conta
            throw ApiException.unauthorized(INVALID_LOGIN);
        }
        Instant now = clock.instant();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw ApiException.tooManyRequests(LOCKED);
        }
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            registerFailure(user, now);
            throw ApiException.unauthorized(INVALID_LOGIN);
        }
        user.setFailedLogins(0);
        user.setLockCount(0);
        user.setLockedUntil(null);
        return session(user);
    }

    @Transactional(noRollbackFor = ApiException.class)   // a revogação por reuso precisa ser gravada com o 401
    public Session refresh(String rawRefreshToken) {
        RefreshTokens.Issued issued = refreshTokens.rotate(rawRefreshToken);
        User user = reload(issued.userId());
        return new Session(new AuthResponse(jwt.generate(user), UserView.of(user)), issued.raw());
    }

    @Transactional
    public void logout(User current) {
        User user = reload(current.getId());
        user.revokeTokens();
        refreshTokens.revokeAll(user.getId());
    }

    @Transactional
    public Session changePassword(User current, ChangePasswordRequest req) {
        User user = reload(current.getId());
        if (!encoder.matches(req.currentPassword(), user.getPasswordHash())) throw ApiException.badRequest("Invalid password");
        if (encoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("New password must be different from the current one");
        }
        PasswordStrength.problem(req.newPassword(), user.getName(), user.getEmail()).ifPresent(p -> { throw ApiException.badRequest(p); });
        user.setPasswordHash(encoder.encode(req.newPassword()));
        user.revokeTokens();
        refreshTokens.revokeAll(user.getId());
        return session(user);
    }

    /** Usuário gerenciado nesta transação. Some só numa corrida (apagado depois de o filtro validar o token): 401. */
    private User reload(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.unauthorized("Authentication required"));
    }

    private Session session(User user) {
        return new Session(new AuthResponse(jwt.generate(user), UserView.of(user)), refreshTokens.issue(user.getId()));
    }

    private void registerFailure(User user, Instant now) {
        user.setFailedLogins(user.getFailedLogins() + 1);
        if (user.getFailedLogins() >= props.login().maxFailures()) {
            user.setLockCount(user.getLockCount() + 1);
            Duration doubled = Duration.ofMinutes(props.login().lockMinutes()).multipliedBy(1L << Math.min(user.getLockCount() - 1, 10));
            Duration lock = doubled.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : doubled;
            user.setLockedUntil(now.plus(lock));
            user.setFailedLogins(0);
            log.warn("Conta {} bloqueada por {} min após {} falhas", user.getId(), lock.toMinutes(), props.login().maxFailures());
        }
    }
}
