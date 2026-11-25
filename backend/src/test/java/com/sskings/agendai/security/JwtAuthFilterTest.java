package com.sskings.agendai.security;

import com.sskings.agendai.config.AgendaiProperties;
import com.sskings.agendai.support.MutableClock;
import com.sskings.agendai.user.User;
import com.sskings.agendai.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private final JwtService jwt = new JwtService(new AgendaiProperties(new AgendaiProperties.Jwt(JwtServiceTest.SECRET, 15),
            new AgendaiProperties.Refresh(30, true), new AgendaiProperties.Login(5, 15), new AgendaiProperties.RateLimit(20, 120, 1200),
            65536, new AgendaiProperties.Cors(List.of())), new MutableClock());
    private final UserRepository users = mock(UserRepository.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwt, users);
    private final User ana = new User("Ana", "ana@x.com", "hash", Instant.now());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(String authorization) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        if (authorization != null) req.addHeader("Authorization", authorization);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("a requisição sempre segue (a autorização decide depois)").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("Token válido da versão atual autentica o usuário carregado do banco")
    void validToken() throws Exception {
        when(users.findById(ana.getId())).thenReturn(Optional.of(ana));
        Authentication auth = run("Bearer " + jwt.generate(ana));
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(ana);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("Não autentica: sem cabeçalho, outro esquema, token inválido, versão antiga (logout) ou usuário apagado")
    void notAuthenticated() throws Exception {
        assertThat(run(null)).isNull();
        assertThat(run("Basic YTpi")).isNull();
        assertThat(run("Bearer lixo")).isNull();
        String old = jwt.generate(ana);
        ana.revokeTokens();
        when(users.findById(ana.getId())).thenReturn(Optional.of(ana));
        assertThat(run("Bearer " + old)).as("versão antiga").isNull();
        when(users.findById(ana.getId())).thenReturn(Optional.empty());
        assertThat(run("Bearer " + jwt.generate(ana))).as("usuário não existe mais").isNull();
    }

    @Test
    @DisplayName("Pool sem conexão a tempo: 503 'tente de novo' na hora, sem seguir a cadeia (filtro: o handler global não alcança)")
    void poolExhausted() throws Exception {
        when(users.findById(ana.getId())).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("pool"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.addHeader("Authorization", "Bearer " + jwt.generate(ana));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentType()).isEqualTo("application/json");
        assertThat(res.getContentAsString()).isEqualTo("{\"status\":503,\"message\":\"Busy, try again\",\"errors\":null}");
        assertThat(chain.getRequest()).isNull();
    }
}
