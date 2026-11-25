package com.sskings.agendai.auth;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.config.AgendaiProperties;
import com.sskings.agendai.security.JwtService;
import com.sskings.agendai.support.MutableClock;
import com.sskings.agendai.tenant.TenantService;
import com.sskings.agendai.user.User;
import com.sskings.agendai.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Corrida que a integração não alcança: o usuário é apagado entre o filtro validar o token e o serviço rodar. */
class AuthServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokens refreshTokens = mock(RefreshTokens.class);
    private final AuthService service;

    AuthServiceTest() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        AgendaiProperties props = new AgendaiProperties(new AgendaiProperties.Jwt("x".repeat(64), 15),
                new AgendaiProperties.Refresh(30, true), new AgendaiProperties.Login(5, 15), new AgendaiProperties.RateLimit(20),
                65536, new AgendaiProperties.Cors(List.of()));
        service = new AuthService(users, encoder, mock(JwtService.class), refreshTokens, mock(TenantService.class), props, new MutableClock());
    }

    @Test
    @DisplayName("Usuário apagado no meio da requisição: logout, troca de senha e refresh respondem 401, sem revogar nada de ninguém")
    void vanishedUser() {
        User gone = new User("Ana", "ana@x.com", "hash", Instant.now());
        when(users.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.logout(gone)).isInstanceOf(ApiException.class).hasMessage("Authentication required");
        assertThatThrownBy(() -> service.changePassword(gone, new AuthDtos.ChangePasswordRequest("Atual1234", "Nova12345")))
                .isInstanceOf(ApiException.class).hasMessage("Authentication required");
        verifyNoInteractions(refreshTokens);
        when(refreshTokens.rotate("raw")).thenReturn(new RefreshTokens.Issued("novo", UUID.randomUUID()));
        assertThatThrownBy(() -> service.refresh("raw")).isInstanceOf(ApiException.class).hasMessage("Authentication required");
    }
}
